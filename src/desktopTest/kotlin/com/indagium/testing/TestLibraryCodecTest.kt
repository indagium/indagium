package com.indagium.testing

import com.indagium.testing.model.HookItem
import com.indagium.testing.model.OnFailure
import com.indagium.testing.model.ScriptPermission
import com.indagium.testing.model.ScriptTarget
import com.indagium.testing.model.StepCheck
import com.indagium.testing.model.StepExample
import com.indagium.testing.model.TestCase
import com.indagium.testing.store.decodeLibraryFile
import com.indagium.testing.store.decodeSuiteFile
import com.indagium.testing.store.encodeLibraryFile
import com.indagium.testing.store.encodeSuiteFile
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TestLibraryCodecTest {
    private fun JsonObject.withKey(key: String, value: kotlinx.serialization.json.JsonElement) = JsonObject(this + (key to value))

    private fun rootOf(text: String): JsonObject = Json.parseToJsonElement(text) as JsonObject

    @Test
    fun suiteFileUsesTheDocumentedEnvelope() {
        val root = rootOf(encodeSuiteFile(fullSuite()))
        assertEquals("indagium-test-suite", (root["format"] as JsonPrimitive).content)
        assertEquals(1, (root["version"] as JsonPrimitive).content.toInt())
        assertTrue(root["suite"] is JsonObject)
    }

    @Test
    fun libraryFileUsesTheDocumentedEnvelope() {
        val root = rootOf(encodeLibraryFile(listOf("suite-a"), listOf(sampleScript()), listOf(sampleSharedStep())))
        assertEquals("indagium-test-library", (root["format"] as JsonPrimitive).content)
        assertEquals(1, (root["version"] as JsonPrimitive).content.toInt())
        assertEquals(1, (root["suiteOrder"] as JsonArray).size)
        assertEquals(1, (root["scripts"] as JsonArray).size)
        assertEquals(1, (root["sharedSteps"] as JsonArray).size)
    }

    @Test
    fun suiteRoundTripsEveryCheckExampleAndHookType() {
        val suite = fullSuite()

        val decoded = decodeSuiteFile(encodeSuiteFile(suite)).getOrThrow()

        assertEquals(suite, decoded.suite)
        assertFalse(decoded.readOnly)
        val checks = decoded.suite.cases.first().steps.first().checks
        assertEquals(
            listOf(
                StepCheck.LogAppears::class, StepCheck.LogAbsent::class, StepCheck.ScreenJudge::class,
                StepCheck.ScriptResult::class, StepCheck.ScriptResult::class, StepCheck.AskJudge::class,
            ),
            checks.map { it::class },
        )
        assertEquals(
            listOf(StepExample.GoldenScreenshot::class, StepExample.ReferenceLog::class),
            decoded.suite.cases.first().steps.first().examples.map { it::class },
        )
        assertTrue(decoded.suite.setup[0] is HookItem.Script)
        assertTrue(decoded.suite.setup[1] is HookItem.Shared)
    }

    @Test
    fun scriptResultKeepsAnExplicitNullExitCodeDistinctFromTheDefault() {
        val checks = decodeSuiteFile(encodeSuiteFile(fullSuite())).getOrThrow().suite.cases.first().steps.first().checks
            .filterIsInstance<StepCheck.ScriptResult>()
        assertEquals(0, checks[0].exitCode)
        assertNull(checks[1].exitCode)
    }

    @Test
    fun allowedToolsDistinguishesAllFromNoneFromSome() {
        val base = fullSuite()
        val cases = listOf(
            TestCase("case-all", "all", allowedTools = null),
            TestCase("case-none", "none", allowedTools = emptySet()),
            TestCase("case-some", "some", allowedTools = linkedSetOf("tap", "swipe")),
        )
        val decoded = decodeSuiteFile(encodeSuiteFile(base.copy(cases = cases))).getOrThrow().suite
        assertNull(decoded.cases[0].allowedTools)
        assertEquals(emptySet(), decoded.cases[1].allowedTools)
        assertEquals(setOf("tap", "swipe"), decoded.cases[2].allowedTools)
    }

    @Test
    fun orderOfEveryListSurvivesARoundTrip() {
        val suite = fullSuite()
        val reordered = suite.copy(
            cases = suite.cases.reversed().map { it.copy(steps = it.steps.reversed()) },
            setup = suite.setup.reversed(),
        )
        val decoded = decodeSuiteFile(encodeSuiteFile(reordered)).getOrThrow().suite
        assertEquals(reordered.cases.map { it.id }, decoded.cases.map { it.id })
        assertEquals(reordered.cases.map { c -> c.steps.map { it.id } }, decoded.cases.map { c -> c.steps.map { it.id } })
        assertEquals(reordered.setup.map { it.id }, decoded.setup.map { it.id })
    }

    @Test
    fun libraryRoundTripsSuiteOrderScriptsAndSharedSteps() {
        val script = sampleScript()
        val shared = sampleSharedStep()

        val decoded = decodeLibraryFile(encodeLibraryFile(listOf("suite-b", "suite-a"), listOf(script), listOf(shared))).getOrThrow()

        assertEquals(listOf("suite-b", "suite-a"), decoded.suiteOrder)
        assertEquals(listOf(script), decoded.scripts)
        assertEquals(listOf(shared), decoded.sharedSteps)
        assertFalse(decoded.readOnly)
    }

    @Test
    fun unknownKeysAreIgnoredAtEveryLevel() {
        val suite = fullSuite()
        val root = rootOf(encodeSuiteFile(suite))
        val suiteObject = root["suite"] as JsonObject
        val casesWithExtras = JsonArray(
            (suiteObject["cases"] as JsonArray).map { (it as JsonObject).withKey("futureCaseField", JsonPrimitive("x")) },
        )
        val patched = root
            .withKey("topLevelExtra", JsonPrimitive(true))
            .withKey("suite", suiteObject.withKey("futureSuiteField", buildJsonObject { put("a", 1) }).withKey("cases", casesWithExtras))

        val decoded = decodeSuiteFile(patched.toString()).getOrThrow()

        assertEquals(suite, decoded.suite)
        assertFalse(decoded.readOnly)
    }

    @Test
    fun aNewerSuiteFileVersionStillDecodesButIsFlaggedReadOnly() {
        val root = rootOf(encodeSuiteFile(fullSuite())).withKey("version", JsonPrimitive(2))

        val decoded = decodeSuiteFile(root.toString()).getOrThrow()

        assertTrue(decoded.readOnly)
        assertTrue(decoded.suite.readOnly)
        assertEquals("Smoke", decoded.suite.name)
    }

    @Test
    fun aNewerLibraryFileVersionStillDecodesButIsFlaggedReadOnly() {
        val root = rootOf(encodeLibraryFile(listOf("suite-a"), listOf(sampleScript()), emptyList())).withKey("version", JsonPrimitive(7))

        val decoded = decodeLibraryFile(root.toString()).getOrThrow()

        assertTrue(decoded.readOnly)
        assertEquals(listOf("suite-a"), decoded.suiteOrder)
        assertEquals(1, decoded.scripts.size)
    }

    @Test
    fun readOnlyIsNeverWrittenIntoTheFile() {
        val text = encodeSuiteFile(fullSuite().copy(readOnly = true))
        assertFalse(text.contains("readOnly"))
        assertFalse(decodeSuiteFile(text).getOrThrow().readOnly)
    }

    @Test
    fun missingFieldsTakeTheirDefaults() {
        val text = """{"format":"indagium-test-suite","suite":{"id":"suite-min","cases":[{"steps":[{"action":"Do it"}]}]}}"""

        val decoded = decodeSuiteFile(text).getOrThrow()

        assertEquals("", decoded.suite.name)
        assertFalse(decoded.readOnly)
        val step = decoded.suite.cases.single().steps.single()
        assertEquals("Do it", step.action)
        assertEquals(com.indagium.testing.model.DEFAULT_STEP_TIMEOUT_MS, step.timeoutMs)
        assertEquals(OnFailure.STOP_CASE, step.onFailure)
        assertNull(decoded.suite.cases.single().allowedTools)
        assertTrue(decoded.suite.cases.single().id.startsWith("case-"))
    }

    @Test
    fun anUnknownCheckKindIsSkippedAndAnUnknownEnumTakesItsDefault() {
        val text = """
            {"format":"indagium-test-suite","version":1,"suite":{"id":"suite-x","name":"X","cases":[{"id":"case-1","name":"C","steps":[
              {"id":"step-1","action":"a","onFailure":"EXPLODE","checks":[
                {"type":"hologram","id":"chk-1"},{"type":"askJudge","id":"chk-2","text":"ok?"}]}]}]}}
        """.trimIndent()

        val step = decodeSuiteFile(text).getOrThrow().suite.cases.single().steps.single()

        assertEquals(OnFailure.STOP_CASE, step.onFailure)
        assertEquals(listOf("chk-2"), step.checks.map { it.id })
    }

    @Test
    fun missingDuplicateAndUnsafeNestedIdsAreReplacedWithFreshOnes() {
        val text = """
            {"format":"indagium-test-suite","version":1,"suite":{"id":"suite-x","name":"X","cases":[
              {"id":"case-dup","name":"A"},{"id":"case-dup","name":"B"},{"id":"../bad","name":"C"},{"name":"D"}]}}
        """.trimIndent()

        val ids = decodeSuiteFile(text).getOrThrow().suite.cases.map { it.id }

        assertEquals(4, ids.toSet().size)
        assertEquals("case-dup", ids[0])
        assertNotEquals("case-dup", ids[1])
        assertTrue(ids.drop(1).all { it.startsWith("case-") })
    }

    @Test
    fun aSuiteWithAnUnsafeIdIsRejectedBecauseTheIdNamesItsFile() {
        val text = """{"format":"indagium-test-suite","version":1,"suite":{"id":"../etc/passwd","name":"X"}}"""
        assertTrue(decodeSuiteFile(text).isFailure)
    }

    @Test
    fun wrongFormatAndGarbageFailWithAReadableMessage() {
        val notJson = decodeSuiteFile("this is not json")
        val wrongFormat = decodeSuiteFile("""{"format":"indagium-workspace-profile","version":1}""")
        val noSuite = decodeSuiteFile("""{"format":"indagium-test-suite","version":1}""")
        val libraryAsSuite = decodeSuiteFile(encodeLibraryFile(emptyList(), emptyList(), emptyList()))

        listOf(notJson, wrongFormat, noSuite, libraryAsSuite).forEach { assertTrue(it.isFailure) }
        assertTrue(notJson.exceptionOrNull()!!.message!!.contains("not valid JSON"))
        assertTrue(wrongFormat.exceptionOrNull()!!.message!!.contains("not an Indagium test suite"))
    }

    @Test
    fun scriptDefaultsAreSafe() {
        val text = """{"format":"indagium-test-library","version":1,"scripts":[{"toolName":"do_it","commandTemplate":"echo hi"}]}"""

        val script = decodeLibraryFile(text).getOrThrow().scripts.single()

        assertEquals(ScriptTarget.HOST_SHELL, script.target)
        assertEquals(ScriptPermission.ASK, script.permission)
        assertTrue(script.id.startsWith("script-"))
    }

    @Test
    fun encodedOutputIsIndentedJsonAndStableAcrossTwoEncodes() {
        val suite = fullSuite()
        val first = encodeSuiteFile(suite)
        assertTrue(first.contains("\n"))
        assertEquals(first, encodeSuiteFile(decodeSuiteFile(first).getOrThrow().suite))
    }
}

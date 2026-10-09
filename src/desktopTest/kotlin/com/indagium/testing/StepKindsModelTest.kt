package com.indagium.testing

import com.indagium.testing.model.DEFAULT_MANUAL_STEP_TIMEOUT_MS
import com.indagium.testing.model.DEFAULT_STEP_TIMEOUT_MS
import com.indagium.testing.model.ManualCompletion
import com.indagium.testing.model.ScriptCall
import com.indagium.testing.model.ScriptParam
import com.indagium.testing.model.ScriptParamType
import com.indagium.testing.model.ScriptPermission
import com.indagium.testing.model.SharedStep
import com.indagium.testing.model.StepCheck
import com.indagium.testing.model.StepKind
import com.indagium.testing.model.TestScript
import com.indagium.testing.model.TestStep
import com.indagium.testing.model.newCheckId
import com.indagium.testing.model.newScriptId
import com.indagium.testing.model.newSharedStepId
import com.indagium.testing.model.newStepId
import com.indagium.testing.model.usesStepKinds
import com.indagium.testing.model.withKind
import com.indagium.testing.store.StoreResult
import com.indagium.testing.store.TEST_LIBRARY_BASE_FILE_VERSION
import com.indagium.testing.store.TEST_LIBRARY_FILE_VERSION
import com.indagium.testing.store.TEST_SUITE_BASE_FILE_VERSION
import com.indagium.testing.store.TEST_SUITE_FILE_VERSION
import com.indagium.testing.store.TestLibraryStore
import com.indagium.testing.store.decodeLibraryFile
import com.indagium.testing.store.decodeSuiteFile
import com.indagium.testing.store.encodeLibraryFile
import com.indagium.testing.store.encodeSuiteFile
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val USB_WITHIN_MS = 4_000L

/** Manual and script steps: the file version they force, what an older reader makes of them, and the rules a written step must meet. */
class StepKindsModelTest {
    private fun versionOf(text: String): Int = ((Json.parseToJsonElement(text) as JsonObject)["version"] as JsonPrimitive).content.toInt()

    private fun manualStep(vararg checks: StepCheck) = TestStep(
        newStepId(), "Plug the USB stick into the phone", checks = checks.toList(), timeoutMs = DEFAULT_MANUAL_STEP_TIMEOUT_MS, kind = StepKind.MANUAL,
        manualCompletion = ManualCompletion.CHECKS_ONLY,
    )

    private fun usbCheck() = StepCheck.DeviceState(newCheckId(), "usb.devices", "0781:5581", USB_WITHIN_MS)

    private fun <T> StoreResult<T>.problem(): String = assertIs<StoreResult.Invalid>(this, "expected a refusal but was $this").reason

    // ── Codec ───────────────────────────────────────────────────────

    @Test
    fun aSuiteOfAiStepsOnlyKeepsTheBaseVersionAndTheOldShape() {
        val text = encodeSuiteFile(fullSuite())

        assertEquals(TEST_SUITE_BASE_FILE_VERSION, versionOf(text))
        assertFalse(text.contains("\"kind\""), "an AI step is written exactly as before step kinds existed")
        assertFalse(text.contains("manualCompletion"))
        assertFalse(Regex("\"script\"\\s*:").containsMatchIn(text), "no script call is written for an AI step")
    }

    @Test
    fun aManualOrScriptStepOrADeviceStateCheckForcesTheStepKindsVersion() {
        val script = sampleScript()
        val manual = suiteWithCases("S", 1).let { it.copy(cases = it.cases.map { c -> c.copy(steps = listOf(manualStep(usbCheck()))) }) }
        val scripted = suiteWithCases("S", 1).let {
            val hubOff = TestStep(newStepId(), "Hub off", kind = StepKind.SCRIPT, script = ScriptCall(script.id))
            it.copy(cases = it.cases.map { c -> c.copy(steps = listOf(hubOff)) })
        }
        val onlyACheck = suiteWithCases("S", 1).let {
            it.copy(cases = it.cases.map { c -> c.copy(steps = listOf(plainStep().copy(checks = listOf(usbCheck())))) })
        }

        listOf(manual, scripted, onlyACheck).forEach { suite ->
            assertTrue(suite.usesStepKinds())
            assertEquals(TEST_SUITE_FILE_VERSION, versionOf(encodeSuiteFile(suite)), suite.cases.single().steps.single().toString())
        }
        assertTrue(TEST_SUITE_FILE_VERSION > TEST_SUITE_BASE_FILE_VERSION)
    }

    @Test
    fun anOlderReaderOpensAStepKindsSuiteReadOnlyInsteadOfHandingItToAnAgent() {
        val suite = suiteWithCases("S", 1).let { it.copy(cases = it.cases.map { c -> c.copy(steps = listOf(manualStep(usbCheck()))) }) }
        val written = versionOf(encodeSuiteFile(suite))

        // A build that predates step kinds reads versions up to TEST_SUITE_BASE_FILE_VERSION and flags anything newer as read-only.
        assertTrue(written > TEST_SUITE_BASE_FILE_VERSION)
        val newer = decodeSuiteFile(encodeSuiteFile(suite).replace("\"version\": $written", "\"version\": ${TEST_SUITE_FILE_VERSION + 1}")).getOrThrow()
        assertTrue(newer.readOnly, "this build also flags a version it does not know as read-only")
        assertFalse(decodeSuiteFile(encodeSuiteFile(suite)).getOrThrow().readOnly)
    }

    @Test
    fun stepKindsRoundTripWithTheirScriptCallAndDeviceStateCheck() {
        val script = sampleScript()
        val call = ScriptCall(script.id, mapOf("package_name" to "com.example.app"), exitCode = null, stdoutContains = "ok")
        val steps = listOf(
            manualStep(usbCheck()).copy(script = call),
            TestStep(newStepId(), "Hub on", kind = StepKind.SCRIPT, script = ScriptCall(script.id, emptyMap())),
        )
        val suite = suiteWithCases("S", 1).let { it.copy(cases = it.cases.map { c -> c.copy(steps = steps) }) }

        val decoded = decodeSuiteFile(encodeSuiteFile(suite)).getOrThrow().suite

        assertEquals(suite.cases, decoded.cases)
        assertNull(decoded.cases.single().steps[0].script?.exitCode, "an explicit null exit code (any) survives")
        assertEquals(0, decoded.cases.single().steps[1].script?.exitCode, "the default exit code is 0")
    }

    @Test
    fun aManualStepWithoutATimeoutInTheFileGetsTheManualDefaultAndOtherStepsKeepTheirs() {
        val text = """{"format":"indagium-test-suite","version":3,"suite":{"id":"suite-a","cases":[{"steps":[
            {"action":"Plug it in","kind":"MANUAL"},{"action":"Tap it"},{"action":"Run it","kind":"SCRIPT","script":{"scriptId":"script-x"}}]}]}}"""

        val steps = decodeSuiteFile(text).getOrThrow().suite.cases.single().steps

        assertEquals(DEFAULT_MANUAL_STEP_TIMEOUT_MS, steps[0].timeoutMs)
        assertEquals(ManualCompletion.TESTER_OR_CHECKS, steps[0].manualCompletion)
        assertEquals(DEFAULT_STEP_TIMEOUT_MS, steps[1].timeoutMs)
        assertEquals(StepKind.AGENT, steps[1].kind)
        assertEquals("script-x", steps[2].script?.scriptId)
    }

    @Test
    fun theLibraryFileGetsTheNewVersionOnlyWhenASharedStepNeedsIt() {
        val plain = encodeLibraryFile(listOf("suite-a"), listOf(sampleScript()), listOf(sampleSharedStep()))
        val shared = SharedStep(newSharedStepId(), "Plug in", steps = listOf(manualStep()))
        val withKinds = encodeLibraryFile(listOf("suite-a"), emptyList(), listOf(shared))

        assertEquals(TEST_LIBRARY_BASE_FILE_VERSION, versionOf(plain))
        assertEquals(TEST_LIBRARY_FILE_VERSION, versionOf(withKinds))
        assertEquals(shared, decodeLibraryFile(withKinds).getOrThrow().sharedSteps.single())
    }

    // ── Helpers ─────────────────────────────────────────────────────

    @Test
    fun changingAStepsKindMovesTheDefaultTimeoutAndDropsWhatTheNewKindCannotUse() {
        val agent = TestStep(newStepId(), "Do it", optional = true, condition = "an ad is shown", script = null)

        val manual = agent.withKind(StepKind.MANUAL)
        assertEquals(DEFAULT_MANUAL_STEP_TIMEOUT_MS, manual.timeoutMs)
        assertFalse(manual.optional)
        assertNull(manual.condition)

        val custom = agent.copy(timeoutMs = 5_000L).withKind(StepKind.MANUAL)
        assertEquals(5_000L, custom.timeoutMs, "a timeout somebody chose is kept")

        val back = manual.withKind(StepKind.AGENT)
        assertEquals(DEFAULT_STEP_TIMEOUT_MS, back.timeoutMs)
        assertNull(manual.copy(script = ScriptCall("script-x")).withKind(StepKind.AGENT).script)
    }

    // ── Validation through the store ────────────────────────────────

    private class Fixture(val store: TestLibraryStore = TestLibraryStore(tempTestingDir())) {
        val suite = (store.createSuite("S") as StoreResult.Ok).value
        val case = (store.createCase(suite.id, plainCase("C")) as StoreResult.Ok).value

        private var scripts = 0

        fun script(permission: ScriptPermission = ScriptPermission.AUTO, params: List<ScriptParam> = emptyList()): TestScript {
            val script = TestScript(newScriptId(), "tool_${scripts++}", params = params, commandTemplate = "true", permission = permission)
            return (store.createScript(script) as StoreResult.Ok).value
        }

        fun create(step: TestStep) = store.createStep(case.id, step)
    }

    @Test
    fun aScriptStepNeedsAnExistingScriptThatMayBeStartedFromAStepAndAcceptsItsArguments() {
        val f = Fixture()
        val auto = f.script()
        val setupOnly = f.script(ScriptPermission.SETUP_TEARDOWN_ONLY)
        val withParam = f.script(params = listOf(ScriptParam("count", ScriptParamType.INT, required = true)))

        fun scripted(call: ScriptCall?) = TestStep("", "Run it", kind = StepKind.SCRIPT, script = call)

        assertTrue(f.create(scripted(null)).problem().contains("needs a script"))
        assertTrue(f.create(scripted(ScriptCall("script-missing"))).problem().contains("not in the library"))
        assertTrue(f.create(scripted(ScriptCall(setupOnly.id))).problem().contains("setup and teardown"))
        assertTrue(f.create(scripted(ScriptCall(auto.id, mapOf("nope" to "1")))).problem().contains("Unknown argument"))
        assertTrue(f.create(scripted(ScriptCall(withParam.id))).problem().contains("Missing required argument 'count'"))
        assertTrue(f.create(scripted(ScriptCall(withParam.id, mapOf("count" to "x")))).problem().contains("whole number"))
        assertIs<StoreResult.Ok<TestStep>>(f.create(scripted(ScriptCall(withParam.id, mapOf("count" to "2")))))
        assertIs<StoreResult.Ok<TestStep>>(f.create(scripted(ScriptCall(auto.id))))
    }

    @Test
    fun aManualStepNeedsInstructionsAndChecksWhenItCompletesByThem() {
        val f = Fixture()

        assertTrue(f.create(TestStep("", " ", kind = StepKind.MANUAL)).problem().contains("instructions"))
        assertTrue(f.create(manualStep().copy(id = "")).problem().contains("at least one log-appears or device-state check"))
        val optionalManual = manualStep().copy(id = "", manualCompletion = ManualCompletion.TESTER_ONLY, optional = true, condition = "x")
        assertTrue(f.create(optionalManual).problem().contains("optional"))
        assertIs<StoreResult.Ok<TestStep>>(f.create(manualStep(usbCheck()).copy(id = "")))
        assertIs<StoreResult.Ok<TestStep>>(f.create(manualStep().copy(id = "", manualCompletion = ManualCompletion.TESTER_ONLY)))
        val withAlternative = manualStep().copy(id = "", manualCompletion = ManualCompletion.TESTER_OR_CHECKS, script = ScriptCall(f.script().id))
        assertIs<StoreResult.Ok<TestStep>>(f.create(withAlternative))
    }

    @Test
    fun anAiStepCannotCarryAScriptAndADeviceStateCheckNeedsAKeyAndAValidExpression() {
        val f = Fixture()
        val script = f.script()

        assertTrue(f.create(TestStep("", "Tap", script = ScriptCall(script.id))).problem().contains("Only manual and script steps"))
        assertTrue(f.create(TestStep("", "Tap", checks = listOf(StepCheck.DeviceState(newCheckId(), " ", "x")))).problem().contains("state key"))
        val badRegex = StepCheck.DeviceState(newCheckId(), "usb.devices", "(")
        assertTrue(f.create(TestStep("", "Tap", checks = listOf(badRegex))).problem().contains("regular expression"))
        assertIs<StoreResult.Ok<TestStep>>(f.create(TestStep("", "Tap", checks = listOf(usbCheck()))))
    }

    @Test
    fun updatingAStepChecksItsScriptAgainstTheLibraryButOtherEditsOfOlderStepsAreNotBlocked() {
        val f = Fixture()
        val script = f.script()
        val step = (f.create(TestStep("", "Run it", kind = StepKind.SCRIPT, script = ScriptCall(script.id))) as StoreResult.Ok).value

        assertTrue(f.store.updateStep(step.id) { it.copy(script = ScriptCall("script-gone")) }.problem().contains("not in the library"))

        f.store.deleteScript(script.id)
        // The step already pointed at the deleted script; renaming the case must still work (data is never dropped).
        assertIs<StoreResult.Ok<*>>(f.store.updateCase(f.case.id) { it.copy(name = "Renamed") })
        val saved = f.store.library.value.findStep(step.id)?.step
        assertEquals(script.id, saved?.script?.scriptId)
    }

    @Test
    fun aStepKindsSuiteIsWrittenAtTheNewVersionOnDisk() {
        val dir = tempTestingDir()
        val f = Fixture(TestLibraryStore(dir))
        f.create(manualStep(usbCheck()).copy(id = ""))

        val text = File(dir, "suites/${f.suite.id}.json").readText()

        assertEquals(TEST_SUITE_FILE_VERSION, versionOf(text))
        assertEquals(f.store.library.value, TestLibraryStore(dir).library.value)
    }
}

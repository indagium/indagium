@file:Suppress("MagicNumber") // Fixture timestamps and counts, not tunable constants.

package com.indagium.testing

import com.indagium.capture.mirror.MirrorTouchAction
import com.indagium.testing.authoring.EnvironmentEvent
import com.indagium.testing.authoring.InputSource
import com.indagium.testing.authoring.RecordedInputKind
import com.indagium.testing.authoring.RecordedTestStep
import com.indagium.testing.authoring.RecordingRewrite
import com.indagium.testing.authoring.RecordingRewriteService
import com.indagium.testing.authoring.RewriteGeneration
import com.indagium.testing.authoring.TestStepRecordingSession
import com.indagium.testing.authoring.parseRewriteResponse
import com.indagium.testing.authoring.rewriteExpectations
import com.indagium.testing.authoring.rewriteInputsOf
import com.indagium.testing.authoring.touchCommand
import com.indagium.testing.model.ManualCompletion
import com.indagium.testing.model.ScriptCall
import com.indagium.testing.model.ScriptParam
import com.indagium.testing.model.ScriptParamType
import com.indagium.testing.model.ScriptPermission
import com.indagium.testing.model.StepCheck
import com.indagium.testing.model.StepKind
import com.indagium.testing.model.TestLibrary
import com.indagium.testing.model.TestScript
import com.indagium.testing.store.StoreResult
import kotlinx.coroutines.runBlocking
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The AI rewrite of a recording with external events: the brief names them and the library's scripts, and the answer may make an
 * external event a manual or a script step - validated against the library - and nothing else anything but an AI step.
 */
class RecordingRewriteExternalTest {
    private val sessions = mutableListOf<TestStepRecordingSession>()

    @AfterTest
    fun closeSessions() = sessions.forEach(TestStepRecordingSession::close)

    private val hubOn = TestScript(
        id = "script-hub-on",
        toolName = "usb_hub_on",
        description = "Turns a port of the USB hub on",
        params = listOf(ScriptParam("port", ScriptParamType.INT)),
        commandTemplate = "hub on \$port",
        permission = ScriptPermission.AUTO,
    )
    private val setupOnly = TestScript("script-setup", "setup_only", commandTemplate = "true", permission = ScriptPermission.SETUP_TEARDOWN_ONLY)
    private val library = shopLibrary().copy(scripts = listOf(hubOn, setupOnly))
    private val usbIn = EnvironmentEvent("usb.devices", "", "0781:5581 Ultra", atMs = 9_000, sinceMs = 8_000)
    private val wifiOff = EnvironmentEvent("wifi.enabled", "true", "false", atMs = 1_900, sinceMs = 1_500)

    /** Input 1 is a tap that turned Wi-Fi off, input 2 a USB device appearing on its own. */
    private fun session(vararg extraRows: RecordedTestStep): TestStepRecordingSession {
        val session = TestStepRecordingSession("fixture-device").also { sessions += it }
        session.accept(touchCommand(MirrorTouchAction.DOWN, 540, 1200), null, 960, InputSource.DEVICE)
        session.accept(touchCommand(MirrorTouchAction.UP, 540, 1200), null, 1_000, InputSource.DEVICE)
        session.environmentSink.environmentEvents(listOf(wifiOff, usbIn))
        runBlocking { session.stopAndDrain() }
        extraRows.forEachIndexed { index, row -> assertTrue(session.insertReviewedRow(index, row)) }
        return session
    }

    private fun service(
        scripts: TestLibrary = library,
        generate: suspend (RewriteGeneration) -> String,
    ) = RecordingRewriteService(library = { scripts }, preflight = { _, _ -> StoreResult.Ok(Unit) }, generate = generate, timeoutMs = 5_000L)

    private fun rewrite(session: TestStepRecordingSession, answer: String, scripts: TestLibrary = library) =
        runBlocking { service(scripts) { answer }.rewrite(session, SHOP_SUITE_ID, SHOP_CASE_ID, "profile", null, null, "") }

    private val tapStep = """{"action":"Tap the button","expected":"A dialog opens","sourceInputs":[1]}"""

    private fun answer(external: String) = """{"steps":[$tapStep,$external]}"""

    private fun rejected(session: TestStepRecordingSession, external: String, vararg reasonParts: String) {
        val before = session.snapshot.value.steps
        val reason = assertIs<StoreResult.Invalid>(rewrite(session, answer(external))).reason
        reasonParts.forEach { assertTrue(it in reason, "'$it' missing from: $reason") }
        assertEquals(before, session.snapshot.value.steps, "a rejected answer changes nothing")
        assertNull(session.snapshot.value.rawSteps)
    }

    // ── What the AI is told ──────────────────────────────────────────

    @Test
    fun theBriefNamesTheExternalEventTheEvidenceOnTheInputAndTheLibraryScripts() {
        val session = session()
        var request: RewriteGeneration? = null

        val manual = answer("""{"action":"Plug in","expected":"","kind":"manual","sourceInputs":[2]}""")
        runBlocking { service { request = it; manual }.rewrite(session, SHOP_SUITE_ID, SHOP_CASE_ID, "profile", null, null, "") }

        val prompt = requireNotNull(request).prompt
        assertTrue("device changes after it: Wi-Fi turned off" in prompt, prompt)
        assertTrue("2. [EXTERNAL EVENT] detected: USB device 'Ultra' (0781:5581) connected" in prompt, prompt)
        assertTrue("proposed step for a tester: \"Connect the USB device 'Ultra' (0781:5581) to the phone\"" in prompt, prompt)
        assertTrue("proposed check: usb.devices matches /0781:5581/" in prompt, prompt)
        assertTrue("<untrusted_data source=\"library_scripts\">" in prompt && "id=script-hub-on | tool=usb_hub_on" in prompt, prompt)
        assertTrue("port:int (required)" in prompt, prompt)
        assertFalse("script-setup" in prompt, "a script limited to setup and teardown is never offered to a step")
        assertTrue("\"kind\"" in prompt && "usb.devices: " in prompt && "manualCompletion" in prompt, prompt)
        assertTrue("[EXTERNAL EVENT]" in request!!.systemPrompt && "never invent a script" in request!!.systemPrompt, request!!.systemPrompt)
    }

    @Test
    fun withoutScriptsTheBriefSaysSo() {
        val session = session()
        var prompt = ""

        runBlocking {
            service(scripts = shopLibrary()) { prompt = it.prompt; answer("""{"action":"Plug in","expected":"","kind":"manual","sourceInputs":[2]}""") }
                .rewrite(session, SHOP_SUITE_ID, SHOP_CASE_ID, "profile", null, null, "")
        }

        assertTrue("no script a step may run" in prompt, prompt)
    }

    // ── What the AI may answer ───────────────────────────────────────

    @Test
    fun anExternalEventBecomesAManualStepThatKeepsItsProposedCheck() {
        val session = session()

        val result = rewrite(session, answer("""{"action":"Plug the USB stick into the phone","expected":"","kind":"manual","sourceInputs":[2]}"""))

        assertIs<StoreResult.Ok<RecordingRewrite>>(result)
        val rows = session.snapshot.value.steps
        val external = rows[1]
        assertEquals(StepKind.MANUAL, external.stepKind)
        assertEquals(RecordedInputKind.EXTERNAL, external.kind)
        assertEquals("Plug the USB stick into the phone", external.action)
        assertEquals("usb.devices", (external.checks.single() as StepCheck.DeviceState).key, "the proposed check is carried over")
        assertEquals(listOf(usbIn), external.stateChanges)
        assertEquals(ManualCompletion.TESTER_OR_CHECKS, external.manualCompletion)
        assertEquals(1, external.sourceInputIds.size)
        assertEquals(StepKind.AGENT, rows[0].stepKind)
        assertEquals(listOf(wifiOff), rows[0].stateChanges, "the evidence on the tap goes with its step")

        assertTrue(session.restoreRaw())
        assertEquals(RecordedInputKind.EXTERNAL, session.snapshot.value.steps[1].kind)
        assertEquals(StepKind.MANUAL, session.snapshot.value.steps[1].stepKind)
    }

    @Test
    fun anExternalEventBecomesAScriptStepWithAValidatedCall() {
        val session = session()

        val result = rewrite(
            session,
            answer("""{"action":"Switch the hub port on","expected":"","kind":"script","scriptId":"script-hub-on","args":{"port":2},"sourceInputs":[2]}"""),
        )

        assertIs<StoreResult.Ok<RecordingRewrite>>(result)
        val external = session.snapshot.value.steps[1]
        assertEquals(StepKind.SCRIPT, external.stepKind)
        assertEquals(ScriptCall("script-hub-on", mapOf("port" to "2")), external.script)
        assertEquals(1, external.checks.size)
    }

    @Test
    fun aManualStepMayCarryAScriptAsItsAutomationAlternativeAndItsOwnChecks() {
        val session = session()

        val result = rewrite(
            session,
            answer(
                """{"action":"Plug it in","expected":"","kind":"manual","scriptId":"script-hub-on","args":{"port":"1"},
                |"manualCompletion":"CHECKS_ONLY","checks":[{"type":"deviceState","key":"usb.devices","expectedRegex":"Ultra","withinMs":60000}],
                |"sourceInputs":[2]}""".trimMargin(),
            ),
        )

        assertIs<StoreResult.Ok<RecordingRewrite>>(result)
        val external = session.snapshot.value.steps[1]
        assertEquals(StepKind.MANUAL, external.stepKind)
        assertEquals("script-hub-on", external.script?.scriptId)
        assertEquals(ManualCompletion.CHECKS_ONLY, external.manualCompletion)
        assertEquals("Ultra", (external.checks.single() as StepCheck.DeviceState).expectedRegex, "its own check replaces the proposed one")
    }

    @Test
    fun aScriptThatIsNotInTheLibraryOrDoesNotTakeTheArgumentsIsRejected() {
        rejected(
            session(), """{"action":"A","expected":"","kind":"script","scriptId":"script-nope","sourceInputs":[2]}""", "steps[1]", "script-nope", "library",
        )
        rejected(session(), """{"action":"A","expected":"","kind":"script","scriptId":"script-hub-on","sourceInputs":[2]}""", "usb_hub_on")
        rejected(
            session(),
            """{"action":"A","expected":"","kind":"script","scriptId":"script-hub-on","args":{"port":"two"},"sourceInputs":[2]}""",
            "usb_hub_on",
        )
        rejected(session(), """{"action":"A","expected":"","kind":"script","scriptId":"script-setup","sourceInputs":[2]}""", "setup")
        rejected(session(), """{"action":"A","expected":"","kind":"script","sourceInputs":[2]}""", "needs a scriptId")
        rejected(session(), """{"action":"A","expected":"","kind":"manual","args":{"port":1},"sourceInputs":[2]}""", "args needs a scriptId")
    }

    @Test
    fun anExternalEventCanOnlyBeAManualOrScriptStepOfItsOwn() {
        rejected(session(), """{"action":"A","expected":"B","sourceInputs":[2]}""", "external event input 2", "manual or script")
        rejected(session(), """{"action":"A","expected":"B","kind":"agent","sourceInputs":[2]}""", "manual or script")
        val merged = """{"steps":[{"action":"Tap and plug","expected":"","kind":"manual","sourceInputs":[1,2]}]}"""
        val session = session()
        assertTrue(assertIs<StoreResult.Invalid>(rewrite(session, merged)).reason.contains("manual or script step of its own"))
        rejected(session(), """{"action":"A","expected":"","kind":"manual","optional":true,"condition":"c","sourceInputs":[2]}""", "optional")
        rejected(session(), """{"action":"A","expected":"","kind":"robot","sourceInputs":[2]}""", "kind must be agent, manual or script")
    }

    @Test
    fun aRecordedInputStaysAnAiStepWhateverTheAnswerSays() {
        val session = session()
        val wrongKind = """{"steps":[{"action":"Tap","expected":"","kind":"manual","sourceInputs":[1]},
            |{"action":"Plug","expected":"","kind":"manual","sourceInputs":[2]}]}""".trimMargin()

        val reason = assertIs<StoreResult.Invalid>(rewrite(session, wrongKind)).reason

        assertTrue("exactly one external event" in reason, reason)
        val withScript = """{"steps":[{"action":"Tap","expected":"x","scriptId":"script-hub-on","sourceInputs":[1]},
            |{"action":"Plug","expected":"","kind":"manual","sourceInputs":[2]}]}""".trimMargin()
        assertTrue("only valid on a manual or script step" in assertIs<StoreResult.Invalid>(rewrite(session, withScript)).reason)
        val completion = """{"steps":[{"action":"Tap","expected":"x","manualCompletion":"TESTER_ONLY","sourceInputs":[1]},
            |{"action":"Plug","expected":"","kind":"manual","sourceInputs":[2]}]}""".trimMargin()
        assertTrue("only valid on a manual or script step" in assertIs<StoreResult.Invalid>(rewrite(session, completion)).reason)
    }

    @Test
    fun deviceStateChecksAreAllowedInARewriteButScriptChecksStillAreNot() {
        val session = session()
        val withCheck = """{"steps":[
            |{"action":"Tap","expected":"x","sourceInputs":[1],"checks":[{"type":"deviceState","key":"wifi.enabled","expectedRegex":"false"}]},
            |{"action":"Plug","expected":"","kind":"manual","sourceInputs":[2]}]}""".trimMargin()

        assertIs<StoreResult.Ok<RecordingRewrite>>(rewrite(session, withCheck))
        // (Row 1 is a tap with no resolved target, so the review keeps it unproposed; the parsed proposal shows the check was accepted.)
        val proposal = parseRewriteResponse(withCheck, rewriteExpectations(rewriteInputsOf(session.rewriteRows()) { null }, library.scripts))
        assertEquals("wifi.enabled", (proposal.steps[0].checks.single() as StepCheck.DeviceState).key)

        val other = session()
        val scriptCheck = """{"steps":[{"action":"Tap","expected":"x","sourceInputs":[1],"checks":[{"type":"scriptResult","scriptId":"script-hub-on"}]},
            |{"action":"Plug","expected":"","kind":"manual","sourceInputs":[2]}]}""".trimMargin()
        assertTrue("script checks" in assertIs<StoreResult.Invalid>(rewrite(other, scriptCheck)).reason)
        val badCheck = """{"steps":[{"action":"Tap","expected":"x","sourceInputs":[1],"checks":[{"type":"deviceState","key":"wifi.enabled"}]},
            |{"action":"Plug","expected":"","kind":"manual","sourceInputs":[2]}]}""".trimMargin()
        assertTrue("expectedRegex" in assertIs<StoreResult.Invalid>(rewrite(other, badCheck)).reason)
    }

    @Test
    fun aStepTheAuthorWroteIsKeptAsItIsIncludingWhoPerformsIt() {
        val author = RecordedTestStep(action = "Plug the hub in", stepKind = StepKind.MANUAL)
        val session = session(author)
        val keep = """{"action":"Plug the hub in","expected":"","kind":"manual","sourceInputs":[1]}"""
        val rest = """{"action":"Tap","expected":"x","sourceInputs":[2]},{"action":"Plug","expected":"","kind":"manual","sourceInputs":[3]}"""

        val result = rewrite(session, """{"steps":[$keep,$rest]}""")

        assertIs<StoreResult.Ok<RecordingRewrite>>(result)
        assertEquals(author.id, session.snapshot.value.steps[0].id)
        assertEquals(StepKind.MANUAL, session.snapshot.value.steps[0].stepKind)
        assertTrue(session.restoreRaw())

        val flipped = session(author.copy(id = "author-2"))
        val agentKind = """{"steps":[{"action":"Plug the hub in","expected":"","kind":"agent","sourceInputs":[1]},$rest]}"""
        assertTrue("kind must be \"manual\"" in assertIs<StoreResult.Invalid>(rewrite(flipped, agentKind)).reason)
        val scripted = """{"steps":[{"action":"Plug the hub in","expected":"","scriptId":"script-hub-on","sourceInputs":[1]},$rest]}"""
        assertTrue("scriptId" in assertIs<StoreResult.Invalid>(rewrite(flipped, scripted)).reason)
    }
}

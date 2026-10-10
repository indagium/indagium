package com.indagium.testing

import com.indagium.testing.authoring.ImproveStepProposal
import com.indagium.testing.authoring.improvedStepOf
import com.indagium.testing.authoring.isImprovableCheck
import com.indagium.testing.authoring.mergeProposedChecks
import com.indagium.testing.authoring.parseImproveResponse
import com.indagium.testing.model.StepCheck
import com.indagium.testing.model.StepIntent
import com.indagium.testing.model.TestStep
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/** An improvement answer is read strictly: one entry per existing step, in order, and only the words (and a few checks) may change. */
private const val SEARCH_CHECKS =
    ""","checks":[{"id":"chk-ask","type":"askJudge","text":"Is a results list shown?"},{"type":"logAppears","regex":"Results loaded"}]"""

class TestCaseImproveParseTest {
    private val steps = improveSteps()
    private val scripts = listOf(improveScript())

    private fun entry(step: TestStep, extra: String = "", action: String = step.action, expected: String = step.expected, reason: String = "unchanged") =
        """{"stepId":"${step.id}","action":"$action","expected":"$expected","reason":"$reason"$extra}"""

    private fun answer(entries: List<String>) = """{"steps":[${entries.joinToString(",")}]}"""

    private fun unchanged() = steps.map { entry(it) }.toMutableList()

    private fun parse(response: String): List<ImproveStepProposal> = parseImproveResponse(response, steps, scripts)

    private fun problem(response: String): String = assertFailsWith<IllegalArgumentException> { parse(response) }.message.orEmpty()

    @Test
    fun anAnswerThatChangesNothingIsValidAndEveryProposalIsUnchanged() {
        val proposals = parse(answer(unchanged()))
        assertEquals(steps.map { it.id }, proposals.map { it.stepId })
        assertTrue(proposals.none { it.changed })
        assertEquals(listOf(1, 2, 3, 4), proposals.map { it.number })
    }

    @Test
    fun aChangedStepCarriesTheNewWordsTheReasonAndAnIncidentalWarningForAnAdvertisingResult() {
        val response = answer(
            listOf(
                entry(steps[0]),
                entry(steps[1], action = "Tap the search field", expected = "The first result is an advertisement", reason = "Clearer action"),
                entry(steps[2]),
                entry(steps[3]),
            ),
        )

        val search = parse(response)[1]

        assertTrue(search.changed)
        assertEquals("Tap the search field", search.action)
        assertEquals("Clearer action", search.reason)
        assertEquals(steps[1].checks, search.checks, "an absent checks key keeps the checks")
        assertNotEquals(null, search.incidentalWarning, "an expected result that names an ad is flagged, never rejected")
    }

    @Test
    fun theCountTheOrderAndTheIdsAreExact() {
        assertTrue("exactly one entry for each of the 4 existing step" in problem(answer(unchanged().take(3))))
        assertTrue("it returned 5" in problem(answer(unchanged() + entry(steps[0]))))
        val swapped = unchanged().also { val first = it[0]; it[0] = it[1]; it[1] = first }
        assertTrue("steps[0].stepId must be \"$STEP_OPEN\"" in problem(answer(swapped)))
        val renamed = unchanged().also { it[2] = it[2].replace(STEP_USB, "step-invented") }
        assertTrue("steps[2].stepId" in problem(answer(renamed)))
        assertTrue("'steps' array" in problem("""{"notSteps":[]}"""))
        assertTrue("valid JSON" in problem("not json at all"))
    }

    @Test
    fun theKindAndEverythingElseOfHowAStepRunsCannotChange() {
        val kindChange = unchanged().also { it[0] = entry(steps[0], extra = ""","kind":"MANUAL"""") }
        assertTrue("kind cannot change" in problem(answer(kindChange)))
        val sameKind = unchanged().also { it[0] = entry(steps[0], extra = ""","kind":"agent"""") }
        assertTrue(parse(answer(sameKind)).none { it.changed })
        val forbidden = listOf(
            ""","scriptId":"script-reset"""", ""","timeoutMs":5""", ""","onFailure":"CONTINUE"""",
            ""","examples":[{"type":"referenceLog","text":"x"}]""",
        )
        forbidden.forEach { extra ->
            val response = answer(unchanged().also { it[3] = entry(steps[3], extra = extra) })
            assertTrue("cannot be changed by an improvement" in problem(response), extra)
        }
    }

    @Test
    fun aManualOrScriptStepKeepsEverythingButItsWords() {
        val optionalManual = unchanged().also { it[2] = entry(steps[2], extra = ""","optional":true,"condition":"The stick is at hand"""") }
        assertTrue("manual step cannot be optional" in problem(answer(optionalManual)))
        val checksOnScript = unchanged().also { it[3] = entry(steps[3], extra = ""","checks":[{"type":"askJudge","text":"Reset?"}]""") }
        assertTrue("checks cannot change on a script step" in problem(answer(checksOnScript)))
        val reworded = entry(steps[2], action = "Plug the USB stick into the phone", reason = "Clearer")
        val words = unchanged().also { it[2] = reworded }
        val manual = parse(answer(words))[2]
        assertEquals("Plug the USB stick into the phone", manual.action)
        assertEquals(steps[2].checks, manual.checks)
        assertFalse(manual.optional)
        assertEquals(null, manual.incidentalWarning, "only AI steps are checked for incidental content")
    }

    @Test
    fun aBlankExpectedResultIsRefusedForARequiredAiStepButAnOptionalConditionalOneMayBeBlank() {
        val blank = unchanged().also { it[0] = entry(steps[0], expected = "") }
        assertTrue("expected may be blank only for a conditional optional step" in problem(answer(blank)))
        val optional = unchanged().also { it[1] = entry(steps[1], expected = "", extra = ""","optional":true,"condition":"A skippable ad is visible"""") }
        val search = parse(answer(optional))[1]
        assertTrue(search.optional && search.expected.isEmpty())
        val noCondition = unchanged().also { it[1] = entry(steps[1], extra = ""","optional":true""") }
        assertTrue("needs a condition" in problem(answer(noCondition)))
        val orphanCondition = unchanged().also { it[1] = entry(steps[1], extra = ""","condition":"only sometimes"""") }
        assertTrue("condition is only valid when optional" in problem(answer(orphanCondition)))
    }

    @Test
    fun everyEntryNeedsAReasonAndTheActionCannotBeBlank() {
        val noReason = unchanged().also { it[0] = """{"stepId":"$STEP_OPEN","action":"Open the app","expected":"Home is visible"}""" }
        assertTrue("reason is required" in problem(answer(noReason)))
        val blankAction = unchanged().also { it[0] = entry(steps[0], action = " ") }
        assertTrue("steps[0].action" in problem(answer(blankAction)))
        val longReason = unchanged().also { it[0] = entry(steps[0], reason = "x".repeat(501)) }
        assertTrue("reason is longer" in problem(answer(longReason)))
    }

    @Test
    fun textThatOnlyDiffersInItsEdgesStaysExactlyAsSavedSoTheStepIsNotChanged() {
        val padded = unchanged().also { it[0] = entry(steps[0], action = "  Open the app  ", expected = "Home is visible ") }
        assertTrue(parse(answer(padded)).none { it.changed })
    }

    @Test
    fun aWordingImprovementCarriesIntentAndPreconditionWithoutChangingEither() {
        val configured = steps.mapIndexed { index, step ->
            if (index == 0) step.copy(intent = StepIntent.ENSURE_STATE, precondition = "Playback is active") else step
        }
        val entries = configured.mapIndexed { index, step ->
            if (index == 0) {
                entry(
                    step,
                    extra = ""","intent":"ensure_state","precondition":"Playback is active"""",
                    action = "Confirm the home screen is visible",
                    expected = "The home screen is shown",
                    reason = "Clearer state goal",
                )
            } else {
                entry(step, extra = ""","intent":"${step.intent.wire}","precondition":null""")
            }
        }

        val improved = parseImproveResponse(answer(entries), configured, scripts).first()

        assertEquals(StepIntent.ENSURE_STATE, improved.intent)
        assertEquals("Playback is active", improved.precondition)
        assertEquals("Confirm the home screen is visible", improved.action)
        val changedIntent = entries.toMutableList().also {
            it[0] = it[0].replace("ensure_state", "perform_action")
        }
        assertTrue("intent is fixed" in assertFailsWith<IllegalArgumentException> {
            parseImproveResponse(answer(changedIntent), configured, scripts)
        }.message.orEmpty())
    }

    @Test
    fun checksOnlyReplaceTheImprovableOnesAndKeepTheOthersAndTheirIds() {
        val response = answer(
            listOf(
                entry(steps[0], extra = ""","checks":[{"type":"askJudge","text":"Is the home screen shown?"}]""", reason = "Added a question"),
                entry(steps[1], extra = SEARCH_CHECKS, reason = "Log check"),
                entry(steps[2]),
                entry(steps[3]),
            ),
        )

        val proposals = parse(response)

        val open = proposals[0]
        assertTrue(open.checks.first() is StepCheck.ScreenJudge && open.checks.first().id == "chk-shot", "the example-bound check stays first and untouched")
        assertEquals(2, open.checks.size)
        val search = proposals[1]
        assertEquals(listOf("chk-ask"), search.checks.filterIsInstance<StepCheck.AskJudge>().map { it.id }, "a re-sent id keeps its check")
        assertEquals(2, search.checks.size, "the old log check was dropped and a new one added")
        assertTrue(search.checks.last().id != "chk-log")
    }

    @Test
    fun scriptDeviceStateAndExampleBoundChecksCannotBeSentAndAWrongIdIsReplaced() {
        val script = unchanged().also { it[1] = entry(steps[1], extra = ""","checks":[{"type":"deviceState","key":"usb.devices","expectedRegex":"x"}]""") }
        assertTrue("may only hold askJudge" in problem(answer(script)))
        val bound = unchanged().also { it[1] = entry(steps[1], extra = ""","checks":[{"type":"screenJudge","text":"Same","exampleRef":"ex-gold"}]""") }
        assertTrue("example" in problem(answer(bound)))
        val takenId = entry(steps[0], extra = ""","checks":[{"id":"chk-shot","type":"askJudge","text":"Taken id"}]""")
        val clash = parse(answer(unchanged().also { it[0] = takenId }))[0]
        assertEquals(2, clash.checks.size)
        assertEquals(2, clash.checks.map { it.id }.distinct().size, "an id of a locked check is never reused")
    }

    @Test
    fun theSharedRulesAreTheSameForAnEditedStep() {
        val saved = steps[1]
        assertTrue(isImprovableCheck(saved.checks.first()))
        assertFalse(isImprovableCheck(steps[0].checks.first()))
        assertEquals(saved.checks, mergeProposedChecks(saved.checks, saved.checks))
        assertFailsWith<IllegalArgumentException> { improvedStepOf(saved, "", "x", false, null, saved.checks, scripts) }
        assertFailsWith<IllegalArgumentException> { improvedStepOf(saved, "a", "", false, null, saved.checks, scripts) }
        assertFailsWith<IllegalArgumentException> { improvedStepOf(steps[2], "a", "b", true, "when", steps[2].checks, scripts) }
        assertEquals(saved, improvedStepOf(saved, saved.action, saved.expected, saved.optional, saved.condition, saved.checks, scripts))
    }
}

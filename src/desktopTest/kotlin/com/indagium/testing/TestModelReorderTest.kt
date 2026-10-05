package com.indagium.testing

import com.indagium.testing.model.CASE_ID_PREFIX
import com.indagium.testing.model.CHECK_ID_PREFIX
import com.indagium.testing.model.EXAMPLE_ID_PREFIX
import com.indagium.testing.model.SCRIPT_ID_PREFIX
import com.indagium.testing.model.SHARED_STEP_ID_PREFIX
import com.indagium.testing.model.STEP_ID_PREFIX
import com.indagium.testing.model.SUITE_ID_PREFIX
import com.indagium.testing.model.StepCheck
import com.indagium.testing.model.StepExample
import com.indagium.testing.model.TestStep
import com.indagium.testing.model.altArrowTarget
import com.indagium.testing.model.isSafeId
import com.indagium.testing.model.isValidScriptParamName
import com.indagium.testing.model.isValidScriptToolName
import com.indagium.testing.model.moveById
import com.indagium.testing.model.newCaseId
import com.indagium.testing.model.newCheckId
import com.indagium.testing.model.newExampleId
import com.indagium.testing.model.newScriptId
import com.indagium.testing.model.newSharedStepId
import com.indagium.testing.model.newStepId
import com.indagium.testing.model.newSuiteId
import com.indagium.testing.model.scriptParamNameError
import com.indagium.testing.model.scriptToolNameError
import com.indagium.testing.model.withFreshIds
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class TestModelReorderTest {
    private val letters = listOf("a", "b", "c", "d")

    private fun List<String>.move(id: String, to: Int) = moveById(id, to) { it }

    @Test
    fun moveByIdMovesAnElementDownToTheRequestedFinalIndex() {
        assertEquals(listOf("b", "c", "a", "d"), letters.move("a", 2))
    }

    @Test
    fun moveByIdMovesAnElementUpToTheRequestedFinalIndex() {
        assertEquals(listOf("d", "a", "b", "c"), letters.move("d", 0))
    }

    @Test
    fun moveByIdClampsAnOutOfRangeTargetIntoTheList() {
        assertEquals(listOf("b", "c", "d", "a"), letters.move("a", 99))
        assertEquals(listOf("d", "a", "b", "c"), letters.move("d", -5))
    }

    @Test
    fun moveByIdReturnsTheSameListForAnUnknownIdOrAnUnchangedPosition() {
        assertSame(letters, letters.move("zzz", 1))
        assertSame(letters, letters.move("b", 1))
    }

    @Test
    fun moveByIdDoesNotMutateTheOriginalList() {
        val original = letters.toList()
        letters.move("a", 3)
        assertEquals(original, letters)
    }

    @Test
    fun moveByIdWorksOnASingleElementAndAnEmptyList() {
        assertEquals(listOf("only"), listOf("only").move("only", 5))
        assertEquals(emptyList(), emptyList<String>().move("x", 0))
    }

    @Test
    fun altArrowTargetStepsOneRowAndStopsAtTheEdges() {
        assertEquals(1, altArrowTarget(index = 2, size = 4, up = true))
        assertEquals(3, altArrowTarget(index = 2, size = 4, up = false))
        assertNull(altArrowTarget(index = 0, size = 4, up = true))
        assertNull(altArrowTarget(index = 3, size = 4, up = false))
    }

    @Test
    fun altArrowTargetRejectsAnIndexOutsideTheList() {
        assertNull(altArrowTarget(index = -1, size = 4, up = false))
        assertNull(altArrowTarget(index = 4, size = 4, up = true))
        assertNull(altArrowTarget(index = 0, size = 0, up = false))
    }

    @Test
    fun scriptToolNameValidatorAcceptsSnakeCaseAndRejectsEverythingElse() {
        assertTrue(isValidScriptToolName("reset_app"))
        assertTrue(isValidScriptToolName("ab"))
        assertTrue(isValidScriptToolName("a" + "b".repeat(40)))
        assertFalse(isValidScriptToolName("a"))
        assertFalse(isValidScriptToolName("a" + "b".repeat(41)))
        assertFalse(isValidScriptToolName("Reset"))
        assertFalse(isValidScriptToolName("1reset"))
        assertFalse(isValidScriptToolName("reset-app"))
        assertFalse(isValidScriptToolName("reset app"))
        assertFalse(isValidScriptToolName(""))
    }

    @Test
    fun scriptToolNameValidatorRejectsBuiltInLaneToolsAndTrackerPrefix() {
        assertNotNull(scriptToolNameError("tap"))
        assertNotNull(scriptToolNameError("finish_step"))
        assertNotNull(scriptToolNameError("tracker_create_issue"))
    }

    @Test
    fun scriptParamNameValidatorRejectsReservedEnvironmentNames() {
        listOf("path", "home", "device", "package", "run_dir", "case_id", "step_id").forEach {
            assertFalse(isValidScriptParamName(it), "'$it' should be reserved")
        }
        assertTrue(isValidScriptParamName("package_name"))
        assertTrue(isValidScriptParamName("x"))
    }

    @Test
    fun scriptParamNameValidatorRequiresLowercase() {
        assertNotNull(scriptParamNameError("Count"))
        assertNotNull(scriptParamNameError("my-param"))
        assertNotNull(scriptParamNameError("_x"))
        assertNotNull(scriptParamNameError(""))
        assertNull(scriptParamNameError("count_2"))
    }

    @Test
    fun idHelpersProduceTheDocumentedPrefixesAndAreUnique() {
        assertTrue(newSuiteId().startsWith(SUITE_ID_PREFIX))
        assertTrue(newCaseId().startsWith(CASE_ID_PREFIX))
        assertTrue(newStepId().startsWith(STEP_ID_PREFIX))
        assertTrue(newCheckId().startsWith(CHECK_ID_PREFIX))
        assertTrue(newExampleId().startsWith(EXAMPLE_ID_PREFIX))
        assertTrue(newScriptId().startsWith(SCRIPT_ID_PREFIX))
        assertTrue(newSharedStepId().startsWith(SHARED_STEP_ID_PREFIX))
        assertEquals("suite-", SUITE_ID_PREFIX)
        assertEquals("chk-", CHECK_ID_PREFIX)
        assertNotEquals(newCaseId(), newCaseId())
    }

    @Test
    fun generatedIdsAreSafeForFileNames() {
        assertTrue(isSafeId(newSuiteId()))
        assertFalse(isSafeId("../evil"))
        assertFalse(isSafeId("a/b"))
        assertFalse(isSafeId(""))
        assertFalse(isSafeId(".hidden"))
    }

    @Test
    fun stepDefaultsUseTheNamedConstants() {
        val step = TestStep(newStepId(), "x")
        assertEquals(com.indagium.testing.model.DEFAULT_STEP_TIMEOUT_MS, step.timeoutMs)
        assertEquals(com.indagium.testing.model.DEFAULT_STEP_RETRIES, step.retries)
        assertEquals(com.indagium.testing.model.OnFailure.STOP_CASE, step.onFailure)
    }

    @Test
    fun withFreshIdsGivesEveryNestedElementANewIdAndKeepsExampleReferencesPointing() {
        val original = fullSuite()
        val copy = original.withFreshIds()

        fun idsOf(suite: com.indagium.testing.model.TestSuite): List<String> = buildList {
            add(suite.id)
            suite.setup.forEach { add(it.id) }
            suite.teardown.forEach { add(it.id) }
            suite.variables.forEach { add(it.id) }
            suite.cases.forEach { c ->
                add(c.id)
                c.steps.forEach { s ->
                    add(s.id)
                    s.checks.forEach { add(it.id) }
                    s.examples.forEach { add(it.id) }
                }
            }
        }
        assertTrue(idsOf(original).intersect(idsOf(copy).toSet()).isEmpty())
        val step = copy.cases.first().steps.first()
        val screenJudge = step.checks.filterIsInstance<StepCheck.ScreenJudge>().single()
        val golden = step.examples.filterIsInstance<StepExample.GoldenScreenshot>().single()
        assertEquals(golden.id, screenJudge.exampleRef)
        assertEquals(original.cases.first().steps.first().action, step.action)
    }
}

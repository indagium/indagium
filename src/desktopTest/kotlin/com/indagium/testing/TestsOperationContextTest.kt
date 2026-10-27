package com.indagium.testing

import com.indagium.ui.RecordingCaseDestination
import com.indagium.ui.recordingTestsOperationTarget
import com.indagium.ui.selectedTestsOperationTarget
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class TestsOperationContextTest {
    @Test
    fun selectedCaseIsPinnedToItsSuiteAndStaleSelectionFallsBackToSuite() {
        assertEquals(
            com.indagium.ui.TestsOperationTarget("suite-a", "case-a"),
            selectedTestsOperationTarget("suite-a", "case-a", setOf("case-a", "case-b")),
        )
        assertEquals(
            com.indagium.ui.TestsOperationTarget("suite-a"),
            selectedTestsOperationTarget("suite-a", "deleted-case", setOf("case-a", "case-b")),
        )
    }

    @Test
    fun suiteRecordingResolvesNewOrExistingDestinationOnlyWhenItBelongsToSuite() {
        val cases = setOf("case-a", "case-b")
        assertEquals(
            com.indagium.ui.TestsOperationTarget("suite-a", "case-b"),
            recordingTestsOperationTarget("suite-a", RecordingCaseDestination.ExistingCase("case-b"), suiteCaseIds = cases),
        )
        assertEquals(
            com.indagium.ui.TestsOperationTarget("suite-a", "case-c"),
            recordingTestsOperationTarget("suite-a", RecordingCaseDestination.NewCase, createdCaseId = "case-c", suiteCaseIds = cases + "case-c"),
        )
        assertNull(recordingTestsOperationTarget("suite-a", RecordingCaseDestination.NewCase, suiteCaseIds = cases))
        assertNull(recordingTestsOperationTarget("suite-a", RecordingCaseDestination.ExistingCase("other"), suiteCaseIds = cases))
    }
}

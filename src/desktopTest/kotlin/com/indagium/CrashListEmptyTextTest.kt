package com.indagium

import com.indagium.model.LogAnalysis
import com.indagium.ui.crashListEmptyText
import kotlin.test.Test
import kotlin.test.assertEquals

class CrashListEmptyTextTest {
    private val caughtUp = LogAnalysis(pending = false)
    private val lagging = LogAnalysis(pending = false, analyzedThroughId = 10)

    @Test
    fun theInitialPendingStateIsTheOnlyOneThatSaysAnalyzing() {
        assertEquals("Analyzing crashes…", crashListEmptyText(LogAnalysis(), 5, live = false))
        assertEquals("Analyzing crashes…", crashListEmptyText(LogAnalysis(), 5, live = true))
    }

    @Test
    fun aLiveCaptureWhoseAnalysedPrefixHasNoCrashesSaysNoneFoundYet() {
        assertEquals("No crashes found yet", crashListEmptyText(lagging, 25, live = true))
    }

    @Test
    fun aCompletedAnalysisSaysNoneFoundInThisCategoryLiveOrNot() {
        assertEquals("None found in this category", crashListEmptyText(caughtUp, 25, live = true))
        assertEquals("None found in this category", crashListEmptyText(caughtUp, 25, live = false))
        assertEquals("None found in this category", crashListEmptyText(lagging, 10, live = true))
    }

    @Test
    fun aLaggingNonLiveTabStillSaysAnalyzing() {
        assertEquals("Analyzing crashes…", crashListEmptyText(lagging, 25, live = false))
    }
}

package com.indagium

import com.indagium.model.CustomIssueRule
import com.indagium.model.LogFormatEngine
import com.indagium.model.LogFormatProfile
import com.indagium.model.TabContentState
import com.indagium.ui.AppState
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The debounced tail analysis refresh analyses a profile tab by its profile, as the initial open does. */
class TailProfileAnalysisTest {
    private fun waitUntil(condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(WAIT_SECONDS)
        while (!condition()) {
            check(System.nanoTime() < deadline) { "condition not met within ${WAIT_SECONDS}s" }
            Thread.sleep(10)
        }
    }

    @Test
    fun theTailRefreshKeepsTheProfilesIssueRulesAndRunsNoLogcatCrashAnalysis() {
        val dir = createTempDirectory("tail-profile-analysis").toFile()
        val app = AppState(autosaveFile = File(dir, "state.cache"), autoExportNotes = false, notesDir = File(dir, "notes"))
        try {
            val profile = LogFormatProfile(
                id = "tail-issue-profile",
                name = "Tail issue profile",
                engine = LogFormatEngine.PLAIN_TEXT,
                issueRules = listOf(CustomIssueRule("rule-timeout", "Upstream timeout", "upstream timeout")),
            )
            assertTrue(app.saveLogFormatProfile(profile).isSuccess)
            val file = File(dir, "service.log").apply { writeText("starting\nupstream timeout on shard 1\n") }
            val tabId = requireNotNull(app.openFileWithLogFormatProfile(file, profile.id))
            // This profile has no Logcat analysis to wait for, so the open's analysis is not "pending": wait for its finding.
            waitUntil { app.tab(tabId)?.contentState == TabContentState.LOADED && app.tab(tabId)?.analysis?.customIssueSites?.isNotEmpty() == true }
            assertEquals(listOf(2), app.tab(tabId)!!.analysis.customIssueSites.map { it.entry.id }, "fixture: the profile's rule fires on open")

            app.startTailing(tabId)
            file.appendText(
                "FATAL EXCEPTION: main\n" +
                    "java.lang.IllegalStateException: boom\n" +
                    "\tat com.app.Main.run(Main.java:10)\n" +
                    "\tat com.app.Main.main(Main.java:5)\n" +
                    "upstream timeout on shard 2\n",
            )
            waitUntil { app.tab(tabId)!!.logData.size == 7 }
            // Stop's final drain ends in the full analysis refresh (the one the debounce runs): it covers every row.
            app.drainAndStopTailing(tabId)
            waitUntil {
                val tab = app.tab(tabId)!!
                tab.logData.size == 7 && tab.analysis.analyzedThroughId == null
            }

            val analysis = app.tab(tabId)!!.analysis
            assertEquals(listOf(2, 7), analysis.customIssueSites.map { it.entry.id }, "the profile's issue rule still applies to every row")
            assertTrue(analysis.stackTraceGroups.isEmpty(), "a profile without the android-crash analyzer gets no stack groups")
            assertTrue(analysis.crashSites.isEmpty(), "and no Logcat crash sites")
        } finally {
            app.close()
            dir.deleteRecursively()
        }
    }

    @Test
    fun changingTheGlobalIssueRulesKeepsTheProfilesOwnRules() {
        val dir = createTempDirectory("profile-issue-recompute").toFile()
        val app = AppState(autosaveFile = File(dir, "state.cache"), autoExportNotes = false, notesDir = File(dir, "notes"))
        try {
            val profile = LogFormatProfile(
                id = "recompute-issue-profile",
                name = "Recompute issue profile",
                engine = LogFormatEngine.PLAIN_TEXT,
                issueRules = listOf(CustomIssueRule("rule-timeout", "Upstream timeout", "upstream timeout")),
            )
            assertTrue(app.saveLogFormatProfile(profile).isSuccess)
            val file = File(dir, "service.log").apply { writeText("starting\nupstream timeout on shard 1\ndisk full\n") }
            val tabId = requireNotNull(app.openFileWithLogFormatProfile(file, profile.id))
            waitUntil { app.tab(tabId)?.contentState == TabContentState.LOADED && app.tab(tabId)?.analysis?.customIssueSites?.isNotEmpty() == true }

            // A global rule added in Settings recomputes every tab's findings: the profile's own rule must still count.
            app.updateSettings { it.copy(customIssueRules = listOf(CustomIssueRule("rule-disk", "Disk full", "disk full"))) }
            waitUntil { app.tab(tabId)!!.analysis.customIssueSites.any { it.entry.id == 3 } }

            assertEquals(listOf(2, 3), app.tab(tabId)!!.analysis.customIssueSites.map { it.entry.id }.sorted())
        } finally {
            app.close()
            dir.deleteRecursively()
        }
    }

    private companion object {
        const val WAIT_SECONDS = 30L
    }
}

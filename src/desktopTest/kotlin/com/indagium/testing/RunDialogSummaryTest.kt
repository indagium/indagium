package com.indagium.testing

import com.indagium.capture.CaptureSettings
import com.indagium.capture.MICROPHONE_DEFAULT_ID
import com.indagium.testing.model.EvidenceFlags
import com.indagium.testing.model.ManualStepsOption
import com.indagium.testing.model.TestCase
import com.indagium.testing.model.TestStep
import com.indagium.testing.run.TestRunReportFormat
import com.indagium.ui.RunDialogModel
import com.indagium.ui.label
import com.indagium.ui.moreSummary
import com.indagium.ui.recordingSummary
import com.indagium.ui.reportExportFileName
import com.indagium.ui.runCasesSummary
import com.indagium.ui.withReportExtension
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals

/** The one-line summaries of the run dialog and the export dialog's file naming (no Compose). */
class RunDialogSummaryTest {
    private fun case(name: String, steps: Int) = TestCase("c-$name", name, steps = List(steps) { TestStep("s-$name-$it", "Do $it", "Done") })

    private val login = case("Login", 2)
    private val checkout = case("Checkout", 3)
    private val search = case("Search", 1)
    private val cases = listOf(login, checkout, search)

    @Test
    fun everyCaseSaysAllWithTheStepTotal() {
        assertEquals("All 3 cases · 6 steps", runCasesSummary(cases, cases.map { it.id }.toSet()))
    }

    @Test
    fun oneCaseIsNamedAndASingularStepIsNotPluralised() {
        assertEquals("Checkout · 3 steps", runCasesSummary(cases, setOf(checkout.id)))
        assertEquals("Search · 1 step", runCasesSummary(cases, setOf(search.id)))
        assertEquals("Untitled case · 0 steps", runCasesSummary(listOf(case("", 0)), setOf("c-")))
    }

    @Test
    fun aSubsetSaysHowManyOfHowMany() {
        assertEquals("2 of 3 cases · 3 steps", runCasesSummary(cases, setOf(login.id, search.id)))
    }

    @Test
    fun noChoiceAndUnknownIdsCountAsNothingSelected() {
        assertEquals("No cases selected", runCasesSummary(cases, emptySet()))
        assertEquals("No cases selected", runCasesSummary(cases, setOf("deleted")))
        assertEquals("No cases selected", runCasesSummary(emptyList(), setOf(login.id)))
    }

    @Test
    fun theDefaultRecordingIsVideoWithoutAudioMicOrEarlierLogs() {
        assertEquals("video to file · no device audio · mic off · no earlier logs", recordingSummary(CaptureSettings(), openLaneTabs = true))
    }

    @Test
    fun recordingOptionsAppearInTheSummary() {
        val settings = CaptureSettings(
            recordVideo = false, audio = true, keepDeviceAudio = true, includeBufferedLogs = true, microphoneDeviceId = MICROPHONE_DEFAULT_ID,
        )
        assertEquals(
            "no video · device audio · mic: system default · earlier logs included · sound kept on the device · no live tab per lane",
            recordingSummary(settings, openLaneTabs = false),
        )
        assertEquals("mic on", recordingSummary(CaptureSettings(microphoneDeviceId = "mixer:usb"), true).split(" · ")[2])
        assertEquals(
            "video to file · no device audio · mic off · no earlier logs",
            recordingSummary(CaptureSettings(audio = false, keepDeviceAudio = true), true),
            "keeping the sound only counts while the device audio is captured",
        )
    }

    @Test
    fun withoutSettingsTheRecordingSaysSo() {
        assertEquals("Recording as in Settings", recordingSummary(null, openLaneTabs = true))
    }

    @Test
    fun theMoreLineListsRetriesRepeatEvidenceAndManualSteps() {
        val model = RunDialogModel("suite", setOf("a"), null, null)
        assertEquals("retries 0 · repeat 1× · evidence: screenshots, transcript", moreSummary(model, showManualSteps = false))
        assertEquals(
            "retries 0 · repeat 1× · evidence: screenshots, transcript · manual steps: script, else wait for tester",
            moreSummary(model, showManualSteps = true),
        )
        val changed = model.copy(
            caseRetries = 1, stepRetriesOverride = 0, repeat = 3, toolLimitText = " 40 ", evidence = EvidenceFlags(screenshots = false, transcript = true),
            manualSteps = ManualStepsOption.WAIT_FOR_TESTER,
        )
        assertEquals(
            "retries 1 · step retries off · repeat 3× · 40 tool calls per case · evidence: transcript · manual steps: wait for tester",
            moreSummary(changed, showManualSteps = true),
        )
        assertEquals("retries 2 · step retries 3 · repeat 5× · no extra evidence", moreSummary(
            model.copy(caseRetries = 2, stepRetriesOverride = 3, repeat = 5, evidence = EvidenceFlags(screenshots = false, transcript = false)),
            showManualSteps = false,
        ))
    }

    @Test
    fun formatsHaveTheLabelsOfTheSegmentedControl() {
        assertEquals(
            listOf("JSON", "Markdown", "Evidence ZIP"),
            TestRunReportFormat.entries.map { it.label() },
        )
    }

    @Test
    fun theReportFileNameIsTheSuiteTheRunTimeAndTheFormatsExtension() {
        val moment = java.time.LocalDateTime.of(2026, 3, 9, 14, 5).toInstant(ZoneOffset.UTC).toEpochMilli()
        assertEquals("Shop_suite_20260309-1405.json", reportExportFileName("Shop suite", moment, TestRunReportFormat.JSON, ZoneOffset.UTC))
        assertEquals("Shop_suite_20260309-1405.md", reportExportFileName("Shop suite", moment, TestRunReportFormat.MARKDOWN, ZoneOffset.UTC))
        assertEquals("Shop_suite_20260309-1405.zip", reportExportFileName("Shop suite", moment, TestRunReportFormat.EVIDENCE_ZIP, ZoneOffset.UTC))
        assertEquals("test_run_20260309-1405.json", reportExportFileName("  / ", moment, TestRunReportFormat.JSON, ZoneOffset.UTC))
    }

    @Test
    fun changingTheFormatSwapsOnlyAKnownExtension() {
        assertEquals("/tmp/out/report.md", withReportExtension("/tmp/out/report.json", TestRunReportFormat.MARKDOWN))
        assertEquals("/tmp/out/report.zip", withReportExtension("/tmp/out/report.MD", TestRunReportFormat.EVIDENCE_ZIP))
        assertEquals("/tmp/out/report.txt", withReportExtension("/tmp/out/report.txt", TestRunReportFormat.JSON), "a name the user chose stays")
        assertEquals("", withReportExtension("", TestRunReportFormat.JSON))
    }
}

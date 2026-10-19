package com.indagium

import com.indagium.model.AnnBlock
import com.indagium.model.Annotations
import com.indagium.model.AppSettings
import com.indagium.model.Filter
import com.indagium.model.LegacyLogRefPreferences
import com.indagium.model.LogEntry
import com.indagium.model.LogLevel
import com.indagium.model.ProcessNameMode
import com.indagium.ui.AppState
import com.indagium.ui.AUTOSAVE_MAGIC_CURRENT
import com.indagium.ui.annotationsFromToken
import com.indagium.ui.annotationsToken
import com.indagium.ui.mkTab
import com.indagium.ui.mkRmap
import com.indagium.ui.tabToken
import com.indagium.utils.buildAnnotationsHtml
import com.indagium.utils.buildMd
import com.indagium.utils.captureAnnotationLogRef
import com.indagium.utils.presentAnnotationLogLine
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import java.io.File
import kotlin.io.path.createTempDirectory

private const val PICKED_PID = 100
private const val OTHER_PID = 200

class AnnotationMetadataSnapshotTest {
    private val rows = listOf(
        LogEntry(1, "10:00:00.000", LogLevel.I, "App", "start", pid = PICKED_PID, tid = 5),
        LogEntry(2, "10:00:00.750", LogLevel.W, "App", "picked process", pid = PICKED_PID, tid = 7),
        LogEntry(3, "10:00:02.000", LogLevel.E, "Other", "unpicked process", pid = OTHER_PID, tid = 9),
    )

    private fun sourceTab() = mkTab("source", "source.log", rows).copy(
        showTimeDelta = true,
        processNameMode = ProcessNameMode.MANUAL,
        manualProcessNamePicks = setOf(PICKED_PID),
        analysis = mkTab("source", "source.log", rows).analysis.copy(
            processNames = mapOf(PICKED_PID to "PickedApp", OTHER_PID to "OtherApp"),
        ),
    )

    @Test
    fun selectedCaptureFreezesSignedDeltaAndManualProcessNameChoicesAcrossSurfacesAndTokenRoundTrip() {
        val source = sourceTab()
        val settingsAtCapture = AppSettings(showRowNumbers = true, copyTimeDelta = false, copyPidTid = false)
        // Include the selected baseline itself and later rows. The anchor must retain its literal
        // 0.000 display across token round-trip and source-view changes.
        val capture = captureAnnotationLogRef(
            tab = source,
            targetIds = listOf(1, 2, 3),
            selection = setOf(1),
            displayedEntries = rows,
            settings = settingsAtCapture,
        )
        assertEquals(AnnBlock.LogRefPresentationOptions(), capture.presentation.options)
        assertEquals("PickedApp", capture.presentation.row(2)?.displayedProcessName)
        assertEquals("OtherApp", capture.presentation.row(3)?.processNameCandidate)
        val enabledPresentation = capture.presentation.copy(
            options = AnnBlock.LogRefPresentationOptions(
                timeDelta = true,
                rowNumber = true,
                pidTid = true,
                processName = true,
            ),
        )
        val block = AnnBlock.LogRef(
            id = "ref",
            logIds = capture.rows.map { it.id },
            caption = "evidence",
            sourceEntries = capture.rows,
            presentation = enabledPresentation,
        )
        assertEquals("+0.750", enabledPresentation.row(2)?.timeDeltaText)
        assertEquals("+2.000", enabledPresentation.row(3)?.timeDeltaText)
        assertEquals("0.000", enabledPresentation.row(1)?.timeDeltaText)
        assertFalse(enabledPresentation.options.allProcessNameCandidates)
        assertTrue(enabledPresentation.options.processName)

        val serialized = Annotations(blocks = listOf(block)).annotationsToken()
        val reopened = assertNotNull(serialized.annotationsFromToken())
        val reopenedBlock = reopened.blocks.single() as AnnBlock.LogRef
        assertEquals(enabledPresentation, reopenedBlock.presentation)

        val changedTab = source.copy(
            selected = setOf(3),
            showTimeDelta = false,
            showUnfiltered = false,
            processNameMode = ProcessNameMode.OFF,
            manualProcessNamePicks = emptySet(),
            analysis = source.analysis.copy(processNames = mapOf(100 to "Renamed", 200 to "Changed")),
            filter = Filter(activeTags = setOf("No matching tag")),
            annotations = reopened,
        )
        val changedCopySettings = AppSettings(
            showRowNumbers = false,
            copyTimeDelta = false,
            copyPidTid = false,
            copyPidAsName = false,
        )
        val expectedPicked = "2  +0.750  10:00:00.750  PickedApp 7  W/App  picked process"
        val expectedUnpicked = "3  +2.000  10:00:02.000  200 9  E/Other  unpicked process"
        val expectedAnchor = "1  0.000  10:00:00.000  PickedApp 5  I/App  start"
        assertEquals(expectedPicked, presentAnnotationLogLine(rows[1], requireNotNull(reopenedBlock.presentation)))
        assertEquals(expectedUnpicked, presentAnnotationLogLine(rows[2], requireNotNull(reopenedBlock.presentation)))

        val markdown = buildMd(changedTab, changedCopySettings)
        val html = buildAnnotationsHtml(changedTab, changedCopySettings)
        assertTrue(markdown.contains(expectedPicked))
        assertTrue(markdown.contains(expectedUnpicked))
        assertTrue(markdown.contains(expectedAnchor))
        assertTrue(html.contains(expectedPicked))
        assertTrue(html.contains(expectedUnpicked))
        assertTrue(html.contains(expectedAnchor))
        assertFalse(markdown.contains("Renamed"))
        assertFalse(html.contains("Changed"))
    }

    @Test
    fun hiddenDeltaCanBeEnabledAndReenabledWithoutRecalculatingCapturedStrings() {
        val source = sourceTab().copy(showTimeDelta = false)
        val capture = captureAnnotationLogRef(source, listOf(2), setOf(1), rows, AppSettings())
        assertFalse(capture.presentation.options.timeDelta)
        assertTrue(capture.presentation.deltaAvailable)
        val firstEnable = capture.presentation.copy(
            options = capture.presentation.options.copy(timeDelta = true),
        )
        val disabled = firstEnable.copy(options = firstEnable.options.copy(timeDelta = false))
        val reenabled = disabled.copy(options = disabled.options.copy(timeDelta = true))
        assertEquals("+0.750", reenabled.row(2)?.timeDeltaText)
        assertEquals("+0.750", firstEnable.row(2)?.timeDeltaText)
        assertTrue(reenabled.deltaAvailable)
    }

    @Test
    fun noSelectionUsesThePreviousDisplayedRowAndUnknownTimestampStaysUnknown() {
        val source = sourceTab().copy(showTimeDelta = true)
        // Filtering/folding can make row 1 the predecessor of row 3 even though row 2 exists in
        // the underlying file. This list is the originating ItemList's actual displayed order.
        val folded = captureAnnotationLogRef(
            tab = source,
            targetIds = listOf(3),
            selection = emptySet(),
            displayedEntries = listOf(rows[0], rows[2]),
            settings = AppSettings(),
        )
        assertEquals("+2.000", folded.presentation.row(3)?.timeDeltaText)

        val missing = rows.map { it.copy(ts = "", dltTimestamp = null) }
        val noTimeTab = mkTab("dlt", "raw.dlt", missing).copy(showTimeDelta = true)
        val unknown = captureAnnotationLogRef(
            tab = noTimeTab,
            targetIds = listOf(2),
            selection = setOf(1),
            displayedEntries = missing,
            settings = AppSettings(),
        )
        assertEquals("—", unknown.presentation.row(2)?.timeDeltaText)
    }

    @Test
    fun compareSourceCanCloseBeforeConfirmAndSameIdsKeepCapturedSourceRows() {
        val root = createTempDirectory("annotation-source-snapshot").toFile()
        val state = AppState(
            autosaveFile = File(root, "session.cache"),
            notesDir = File(root, "notes").apply { mkdirs() },
        )
        try {
            val leftRows = rows.map { it.copy(tag = "Left", msg = "left-${it.msg}") }
            val left = mkTab("left", "left.log", leftRows)
            val right = sourceTab()
            state.tabs = listOf(left, right)
            state.activeTabId = "left"
            state.compareMode = true

            val capture = captureAnnotationLogRef(
                tab = right,
                targetIds = listOf(2),
                selection = setOf(1),
                displayedEntries = rows,
                settings = AppSettings(showRowNumbers = true),
            )
            state.requestAddAnn("right", capture)
            val request = assertNotNull(state.addAnnRequest)

            // Closing the source pane while the dialog is open must not force the later confirm to
            // resolve ids 1 and 2 against the colliding rows still open in the target pane.
            state.tabs = listOf(left)
            state.confirmAddAnn(request, "captured", capture.presentation.options)

            val block = state.tab("left")!!.annotations.blocks.single() as AnnBlock.LogRef
            assertEquals("right", block.sourceTabId)
            assertEquals(rows.drop(1).take(1), block.sourceEntries)
            assertEquals("+0.750", block.presentation?.row(2)?.timeDeltaText)
        } finally {
            state.close()
        }
    }

    @Test
    fun loadingLegacyNotesFreezesRowsWithoutRewritingTheSidecar() {
        val root = createTempDirectory("annotation-legacy-freeze").toFile()
        val state = AppState(
            autosaveFile = File(root, "session.cache"),
            notesDir = File(root, "notes").apply { mkdirs() },
        )
        try {
            val source = sourceTab()
            state.tabs = listOf(source)
            state.updateSettings {
                it.copy(copyRowNumber = true, showRowNumbers = true, copyPidTid = true, copyPidAsName = true)
            }
            val sidecar = File(root, "legacy.ann")
            sidecar.writeText(
                Annotations(
                    blocks = listOf(AnnBlock.LogRef("legacy", listOf(2), "old excerpt", sourceEntries = null)),
                ).annotationsToken(),
            )
            val bytesBeforeOpen = sidecar.readBytes()

            assertTrue(state.loadAnnotationsFrom("source", sidecar))
            val migrated = state.tab("source")!!.annotations.blocks.single() as AnnBlock.LogRef
            assertEquals(listOf(rows[1]), migrated.sourceEntries)
            assertEquals(true, migrated.presentation?.options?.rowNumber)
            assertEquals(true, migrated.presentation?.options?.pidTid)
            assertEquals(true, migrated.presentation?.options?.processName)
            assertFalse(migrated.presentation?.options?.timeDelta ?: true)
            assertEquals(bytesBeforeOpen.toList(), sidecar.readBytes().toList(), "opening a sidecar must not rewrite it")
        } finally {
            state.close()
        }
    }

    @Test
    fun deferredLegacyRestoreKeepsThePreferencesCapturedBeforeRowsLoad() {
        val root = createTempDirectory("annotation-legacy-deferred").toFile()
        val state = AppState(
            autosaveFile = File(root, "session.cache"),
            notesDir = File(root, "notes").apply { mkdirs() },
        )
        try {
            val legacy = Annotations(blocks = listOf(AnnBlock.LogRef("legacy", listOf(2), "old excerpt")))
            val shell = mkTab("source", "source.log", emptyList()).copy(
                annotations = legacy,
                pendingLegacyLogRefPreferences = LegacyLogRefPreferences(
                    copyRowNumber = true,
                    showRowNumbers = true,
                    copyPidTid = true,
                    copyPidAsName = false,
                ),
            )
            state.tabs = listOf(shell)
            state.updateSettings {
                it.copy(copyRowNumber = false, showRowNumbers = false, copyPidTid = false, copyPidAsName = true)
            }
            assertEquals(null, state.tab("source")!!.annotations.blocks.single().let { it as AnnBlock.LogRef }.presentation)

            state.upTab("source") { tab ->
                tab.copy(
                    logData = rows,
                    rmap = mkRmap(rows),
                    analysis = sourceTab().analysis,
                )
            }

            val migrated = state.tab("source")!!.annotations.blocks.single() as AnnBlock.LogRef
            assertEquals(true, migrated.presentation?.options?.rowNumber)
            assertEquals(true, migrated.presentation?.options?.pidTid)
            assertFalse(migrated.presentation?.options?.processName ?: true)
            assertEquals(listOf(rows[1]), migrated.sourceEntries)
        } finally {
            state.close()
        }
    }

    @Test
    fun autosaveShellWithCachedRowsFreezesLegacyPresentationImmediately() {
        val root = createTempDirectory("annotation-legacy-cached-shell").toFile()
        val sourceFile = File(root, "source.log").apply { writeText("cached source") }
        val legacyTab = mkTab("cached", sourceFile.name, emptyList()).copy(
            sourcePath = sourceFile.absolutePath,
            annotations = Annotations(
                blocks = listOf(
                    AnnBlock.LogRef(id = "legacy", logIds = listOf(2), caption = "", sourceEntries = listOf(rows[1])),
                ),
            ),
        )
        val autosave = File(root, "session.cache").apply {
            writeText("$AUTOSAVE_MAGIC_CURRENT\ntabs\ntab\t${legacyTab.tabToken()}\n")
        }
        val state = AppState(
            autosaveFile = autosave,
            restoreOnCreate = true,
            notesDir = File(root, "notes").apply { mkdirs() },
        )
        try {
            val restored = state.tab("cached")!!.annotations.blocks.single() as AnnBlock.LogRef
            assertNotNull(restored.presentation, "cached excerpt rows should migrate in the restore shell")
            assertEquals(listOf(rows[1]), restored.sourceEntries)
            assertFalse(restored.presentation.options.timeDelta)
            assertFalse(restored.presentation.options.pidTid)
        } finally {
            state.close()
        }
    }
}

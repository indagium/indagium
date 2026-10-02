package com.indagium

import com.indagium.model.AnnBlock
import com.indagium.model.AnnotationCopyFormat
import com.indagium.model.Annotations
import com.indagium.model.AppSettings
import com.indagium.model.LogEntry
import com.indagium.model.LogLevel
import com.indagium.ui.AppState
import com.indagium.ui.mkTab
import com.indagium.utils.LogLinePresentationContext
import com.indagium.utils.buildAnnotationsHtml
import com.indagium.utils.buildMd
import com.indagium.utils.visibleEntries
import java.awt.datatransfer.DataFlavor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

// A note copy used to re-filter the whole log (visibleEntries) once per log block per builder, even
// with Δt copying off. These pin that the shared line context is only forced when Δt can use it, and
// then only once per copy no matter how many blocks or builders consume it.
class CopyLineContextSharingTest {
    private val rows = listOf(
        LogEntry(1, "10:00:00.000", LogLevel.I, "App", "start"),
        LogEntry(2, "10:00:00.250", LogLevel.E, "App", "boom"),
        LogEntry(3, "10:00:00.500", LogLevel.W, "App", "again"),
    )

    private fun tab(showTimeDelta: Boolean) = mkTab("log", "test.log", rows).copy(
        showTimeDelta = showTimeDelta,
        annotations = Annotations(
            blocks = listOf(
                AnnBlock.LogRef("a", listOf(2), "First"),
                AnnBlock.LogRef("b", listOf(3), "Second"),
            ),
        ),
    )

    @Test
    fun lineContextIsNeverBuiltWhenTimeDeltaCopyingIsOff() {
        val current = tab(showTimeDelta = true)
        val off = AppSettings(copyTimeDelta = false)
        val shared = lazy<LogLinePresentationContext> { error("visible rows must not be scanned when Δt is off") }

        assertTrue(buildMd(current, off, shared).contains("boom"))
        assertTrue(buildAnnotationsHtml(current, off, lineContext = shared).contains("boom"))

        val tabWithoutDelta = tab(showTimeDelta = false)
        val on = AppSettings(copyTimeDelta = true)
        assertTrue(buildMd(tabWithoutDelta, on, shared).contains("boom"))
    }

    @Test
    fun lineContextIsBuiltOnceAcrossBlocksAndBothBuilders() {
        val current = tab(showTimeDelta = true)
        val on = AppSettings(copyTimeDelta = true)
        var built = 0
        val shared = lazy {
            built++
            LogLinePresentationContext(current, on, visibleEntries(current))
        }

        val markdown = buildMd(current, on, shared)
        val html = buildAnnotationsHtml(current, on, lineContext = shared)

        assertEquals(1, built)
        assertTrue(markdown.contains("+0.250"))
        assertTrue(html.contains("+0.250"))
    }

    @Test
    fun clipboardPayloadIsBuiltOffTheClipboardAndNullForAMissingTab() {
        val state = AppState()
        state.settings = AppSettings(copyTimeDelta = false)
        state.tabs = listOf(tab(showTimeDelta = false))

        val wiki = assertNotNull(state.buildAnnotationClipboardTransferable("log", AnnotationCopyFormat.JIRA_WIKI))
        assertTrue((wiki.getTransferData(DataFlavor.stringFlavor) as String).contains("{code:java}"))

        val cloud = assertNotNull(state.buildAnnotationClipboardTransferable("log", AnnotationCopyFormat.JIRA_CLOUD))
        assertTrue(cloud.isDataFlavorSupported(DataFlavor("text/html;class=java.lang.String")))

        assertNull(state.buildAnnotationClipboardTransferable("missing", AnnotationCopyFormat.MARKDOWN))
    }
}

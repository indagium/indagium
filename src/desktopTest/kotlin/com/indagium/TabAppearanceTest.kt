package com.indagium

import androidx.compose.ui.graphics.Color
import com.indagium.model.AppSettings
import com.indagium.model.LogTab
import com.indagium.model.TabLabelMode
import com.indagium.model.TabStripStyle
import com.indagium.ui.DANGER_RED
import com.indagium.ui.FIT_TAB_MAX_DP
import com.indagium.ui.FIT_TAB_MIN_DP
import com.indagium.ui.LIGHT_THEME
import com.indagium.ui.TabAppearance
import com.indagium.ui.TabKind
import com.indagium.ui.browserTabOrderDuringDrag
import com.indagium.ui.browserTabOrderDuringDragVariable
import com.indagium.ui.fitTabWidthsPx
import com.indagium.ui.logTabDescriptor
import com.indagium.ui.mkTab
import com.indagium.ui.shrinkTabWidthsToFit
import com.indagium.ui.splitTabsForVisibilityFit
import com.indagium.ui.tabBadgeShown
import com.indagium.ui.tabDetailLine
import com.indagium.ui.tabDisplayLabel
import com.indagium.ui.tabFitContentDp
import com.indagium.ui.tabIndexAtX
import com.indagium.ui.tabKind
import com.indagium.ui.tabKindBadge
import com.indagium.ui.tabLabelParts
import com.indagium.ui.tabOffsets
import com.indagium.ui.tabSourceColor
import com.indagium.ui.tabStripHeightDp
import com.indagium.ui.tabStripeShown
import com.indagium.utils.ZipLogCandidate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TabAppearanceTest {
    private fun tab(id: String, name: String, path: String? = null): LogTab = mkTab(id, name, emptyList()).copy(sourcePath = path)

    // ── kind ────────────────────────────────────────────────────────────────

    @Test
    fun kindOfEverySourceKindOfTab() {
        assertEquals(TabKind.LOG, tabKind(tab("a", "a.log", "/logs/a.log")))
        assertEquals(TabKind.LOG, tabKind(tab("a", "a.log")))
        assertEquals(TabKind.HOME, tabKind(tab("h", "New tab").copy(isCaptureLauncher = true)))
        assertEquals(TabKind.LIVE, tabKind(tab("c", "cap.log").copy(captureSessionId = "session")))
        assertEquals(TabKind.LIVE, tabKind(tab("t", "tail.log", "/logs/tail.log").copy(tailing = true)))
        assertEquals(TabKind.ZIP, tabKind(tab("z", "main.log", "/logs/report.zip!FS/main.log")))
        assertEquals(
            TabKind.ZIP,
            tabKind(tab("z", "main.log").copy(archiveCandidate = ZipLogCandidate("FS/main.log", "main.log", 10))),
        )
        assertEquals(TabKind.IND, tabKind(tab("i", "cap.log").copy(captureArchivePath = "/caps/run.ind")))
        assertEquals(TabKind.CAPTURE, tabKind(tab("p", "cap.log").copy(captureArchivePath = "/caps/run.zip")))
        assertEquals(TabKind.CAPTURE, tabKind(tab("s", "cap.log").copy(captureSourceSessionId = "stopped")))
    }

    @Test
    fun aRunningCaptureWinsOverItsArchiveFields() {
        val live = tab("c", "cap.log", "/s/cap.log").copy(captureSessionId = "x", captureArchivePath = "/caps/run.ind")
        assertEquals(TabKind.LIVE, tabKind(live))
    }

    @Test
    fun badgesAgreeWithTheRecentFilesList() {
        assertEquals("LOG", tabKindBadge(TabKind.LOG))
        assertEquals("ZIP", tabKindBadge(TabKind.ZIP))
        assertEquals("IND", tabKindBadge(TabKind.IND))
        TabKind.entries.forEach { assertTrue(tabKindBadge(it).isNotBlank()) }
    }

    @Test
    fun sourceColoursSeparateTheKindsTheDesignNames() {
        val colours = listOf(TabKind.LOG, TabKind.LIVE, TabKind.ZIP, TabKind.IND).map { tabSourceColor(it, LIGHT_THEME) }
        assertEquals(colours.size, colours.toSet().size, "file, live, zip and .ind colours are distinct")
        assertEquals(DANGER_RED, tabSourceColor(TabKind.LIVE, LIGHT_THEME))
        assertEquals(tabSourceColor(TabKind.IND, LIGHT_THEME), tabSourceColor(TabKind.CAPTURE, LIGHT_THEME))
        TabKind.entries.forEach { assertNotEquals(Color.Unspecified, tabSourceColor(it, LIGHT_THEME)) }
    }

    // ── labels ──────────────────────────────────────────────────────────────

    @Test
    fun theDefaultLabelIsExactlyTabDisplayLabelForEveryDisambiguationCase() {
        val first = tab("one", "same.log", "/logs/first/same.log")
        val second = tab("two", "same.log", "/logs/second/same.log")
        val archiveA = tab("archive-a", "same.log", "/logs/reports/archive-a.zip!FS/a/same.log")
        val archiveB = tab("archive-b", "same.log", "/logs/reports/archive-b.zip!FS/b/same.log")
        val sameArchiveA = tab("sa", "same.log", "/logs/x/report.zip!FS/a/same.log")
        val sameArchiveB = tab("sb", "same.log", "/logs/y/report.zip!FS/b/same.log")
        val sameFolderA = tab("real-a-134567", "same.log", "/logs/shared/same.log")
        val sameFolderB = tab("real-b-1234567", "same.log", "/logs/shared/same.log")
        val lone = tab("lone", "lone.log")
        val groups = listOf(
            listOf(first, second), listOf(first), listOf(archiveA, archiveB), listOf(sameArchiveA, sameArchiveB),
            listOf(sameFolderA, sameFolderB), listOf(lone, first, second),
        )
        groups.forEach { all ->
            all.forEach { t ->
                assertEquals(tabDisplayLabel(t, all), tabLabelParts(t, all, TabLabelMode.FILE_SESSION).joined())
            }
        }
        assertEquals("same.log — first", tabDisplayLabel(first, listOf(first, second)))
        assertEquals("same.log — archive-a.zip", tabDisplayLabel(archiveA, listOf(archiveA, archiveB)))
        assertEquals("same.log — shared — real-a-1", tabDisplayLabel(sameFolderA, listOf(sameFolderA, sameFolderB)))
    }

    @Test
    fun labelPartsForEachMode() {
        val first = tab("one", "same.log", "/logs/first/same.log")
        val second = tab("two", "same.log", "/logs/second/same.log")
        val all = listOf(first, second)
        val fileSession = tabLabelParts(first, all, TabLabelMode.FILE_SESSION)
        assertEquals("same.log", fileSession.primary)
        assertEquals("first", fileSession.secondary)
        assertEquals("same.log", tabLabelParts(first, all, TabLabelMode.FILE_ONLY).joined())
        assertEquals("first", tabLabelParts(first, all, TabLabelMode.SESSION_ONLY).joined())
        // Without a collision the session is still the folder; the default shows the bare file name.
        val unique = tab("u", "unique.log", "/logs/capture-2026/unique.log")
        assertEquals("unique.log", tabLabelParts(unique, listOf(unique), TabLabelMode.FILE_SESSION).joined())
        assertEquals("capture-2026", tabLabelParts(unique, listOf(unique), TabLabelMode.SESSION_ONLY).joined())
        assertEquals("unique.log", tabLabelParts(unique, listOf(unique), TabLabelMode.FILE_ONLY).joined())
        // An archive entry's session is its archive; a tab with no path falls back to the file name.
        val entry = tab("e", "main.log", "/logs/report.zip!FS/main.log")
        assertEquals("report.zip", tabLabelParts(entry, listOf(entry), TabLabelMode.SESSION_ONLY).joined())
        assertEquals("pathless.log", tabLabelParts(tab("p", "pathless.log"), emptyList(), TabLabelMode.SESSION_ONLY).joined())
    }

    @Test
    fun fileFolderAlwaysShowsTheFolderEvenWithoutACollision() {
        val unique = tab("u", "unique.log", "/logs/capture-2026/unique.log")
        val alone = listOf(unique)
        assertEquals("unique.log — capture-2026", tabLabelParts(unique, alone, TabLabelMode.FILE_FOLDER_ALWAYS).joined())
        // The default stays the bare name until another open tab shares it; the always-mode label does not change then.
        assertEquals("unique.log", tabLabelParts(unique, alone, TabLabelMode.FILE_SESSION).joined())
        val twin = tab("t", "unique.log", "/logs/other/unique.log")
        val both = listOf(unique, twin)
        assertEquals("unique.log — capture-2026", tabLabelParts(unique, both, TabLabelMode.FILE_FOLDER_ALWAYS).joined())
        assertEquals("unique.log — other", tabLabelParts(twin, both, TabLabelMode.FILE_FOLDER_ALWAYS).joined())
        assertEquals("unique.log — capture-2026", tabLabelParts(unique, both, TabLabelMode.FILE_SESSION).joined())
        // An archive entry shows its archive; the file name and the folder are the two parts.
        val entry = tab("e", "main.log", "/logs/report.zip!FS/main.log")
        val parts = tabLabelParts(entry, listOf(entry), TabLabelMode.FILE_FOLDER_ALWAYS)
        assertEquals("main.log", parts.primary)
        assertEquals("report.zip", parts.secondary)
    }

    @Test
    fun fileFolderAlwaysNeverGivesTwoTabsTheSameLabel() {
        // Same file name in two folders that are both called "shared": the folder alone cannot tell them apart.
        val a = tab("real-a-134567", "same.log", "/x/shared/same.log")
        val b = tab("real-b-1234567", "same.log", "/y/shared/same.log")
        val all = listOf(a, b)
        val labels = all.map { tabLabelParts(it, all, TabLabelMode.FILE_FOLDER_ALWAYS).joined() }
        assertEquals(2, labels.toSet().size, "labels $labels must differ")
        assertTrue(labels.all { it.startsWith("same.log — shared") })
        // A tab with no source path falls back to the disambiguation (the tab id) when it needs one, and is just the file name otherwise.
        val pathlessA = tab("aaaaaaaa-1", "p.log")
        val pathlessB = tab("bbbbbbbb-2", "p.log")
        assertEquals("p.log — aaaaaaaa", tabLabelParts(pathlessA, listOf(pathlessA, pathlessB), TabLabelMode.FILE_FOLDER_ALWAYS).joined())
        assertEquals("p.log", tabLabelParts(pathlessA, listOf(pathlessA), TabLabelMode.FILE_FOLDER_ALWAYS).joined())
    }

    @Test
    fun theTwoLineDetailLineKeepsTheFileNameOnlyWhenTheFirstLineLacksIt() {
        val t = tab("a", "a.log", "/logs/f/a.log")
        assertEquals("0 lines", tabDetailLine(t, TabKind.LOG, TabLabelMode.FILE_FOLDER_ALWAYS))
        assertEquals("a.log · 0 lines", tabDetailLine(t, TabKind.LOG, TabLabelMode.SESSION_ONLY))
    }

    @Test
    fun theDetailLineSaysRowsAndState() {
        val rows = (1..3).map { com.indagium.model.LogEntry(it, "10:00:00.000", com.indagium.model.LogLevel.I, "T", "m$it") }
        val plain = mkTab("a", "a.log", rows).copy(sourcePath = "/logs/f/a.log")
        assertEquals("3 lines", tabDetailLine(plain, TabKind.LOG, TabLabelMode.FILE_SESSION))
        assertEquals("a.log · 3 lines", tabDetailLine(plain, TabKind.LOG, TabLabelMode.SESSION_ONLY))
        assertEquals("3 lines · capturing", tabDetailLine(plain.copy(captureSessionId = "s"), TabKind.LIVE, TabLabelMode.FILE_SESSION))
        assertEquals("3 lines · tailing", tabDetailLine(plain.copy(tailing = true), TabKind.LIVE, TabLabelMode.FILE_SESSION))
        assertEquals("1 line", tabDetailLine(mkTab("b", "b.log", rows.take(1)), TabKind.LOG, TabLabelMode.FILE_ONLY))
    }

    // ── settings -> appearance ───────────────────────────────────────────────

    @Test
    fun theDefaultSettingsGiveTheDefaultAppearance() {
        val t = tab("a", "a.log", "/logs/a.log")
        val d = logTabDescriptor(t, listOf(t), AppSettings())
        assertEquals("a.log", d.label)
        assertEquals(TabAppearance.Default.copy(kind = TabKind.LOG), d.appearance)
        assertEquals(TabStripStyle.CURRENT, d.appearance.style)
        assertFalse(d.appearance.showBadge)
        assertFalse(d.appearance.showStripe)
        assertTrue(d.appearance.closeAlways)
        assertNull(d.appearance.secondLine)
    }

    @Test
    fun typedAndSourceStripeStylesImplyTheirDetailAndTheCheckboxesAddThemToAnyStyle() {
        val base = AppSettings()
        assertFalse(tabBadgeShown(base))
        assertFalse(tabStripeShown(base))
        assertTrue(tabBadgeShown(base.copy(tabStripStyle = TabStripStyle.TYPED)))
        assertFalse(tabStripeShown(base.copy(tabStripStyle = TabStripStyle.TYPED)))
        assertTrue(tabStripeShown(base.copy(tabStripStyle = TabStripStyle.SOURCE_STRIPE)))
        assertFalse(tabBadgeShown(base.copy(tabStripStyle = TabStripStyle.SOURCE_STRIPE)))
        TabStripStyle.entries.forEach { style ->
            assertTrue(tabBadgeShown(base.copy(tabStripStyle = style, tabShowTypeBadge = true)))
            assertTrue(tabStripeShown(base.copy(tabStripStyle = style, tabShowSourceStripe = true)))
        }
    }

    @Test
    fun twoLineStyleAddsASecondLineOnlyThere() {
        val t = tab("a", "a.log", "/logs/a.log")
        assertNull(logTabDescriptor(t, listOf(t), AppSettings(tabStripStyle = TabStripStyle.CURRENT)).appearance.secondLine)
        assertEquals(
            "0 lines",
            logTabDescriptor(t, listOf(t), AppSettings(tabStripStyle = TabStripStyle.TWO_LINE)).appearance.secondLine,
        )
    }

    @Test
    fun stripHeights() {
        assertEquals(28, tabStripHeightDp(TabStripStyle.COMPACT))
        assertEquals(44, tabStripHeightDp(TabStripStyle.TWO_LINE))
        TabStripStyle.entries.filter { it != TabStripStyle.COMPACT && it != TabStripStyle.TWO_LINE }
            .forEach { assertEquals(36, tabStripHeightDp(it), "$it") }
    }

    // ── FIT widths and offsets ───────────────────────────────────────────────

    @Test
    fun fitWidthsFollowTheLabelAndAreClamped() {
        val short = tabFitContentDp("a.log", TabAppearance.Default)
        val long = tabFitContentDp("a-much-longer-file-name.log", TabAppearance.Default)
        assertTrue(long > short)
        // Details widen a tab.
        assertTrue(tabFitContentDp("a.log", TabAppearance.Default.copy(showBadge = true)) > short)
        assertTrue(tabFitContentDp("a.log", TabAppearance.Default.copy(tailing = true)) > short)
        val widths = fitTabWidthsPx(listOf(10f, 100f, 9999f), density = 2f)
        assertEquals(FIT_TAB_MIN_DP * 2f, widths[0])
        assertEquals(200f, widths[1])
        assertEquals(FIT_TAB_MAX_DP * 2f, widths[2])
    }

    @Test
    fun offsetsAreTheRunningSumOfWidths() {
        assertEquals(listOf(0f, 100f, 250f, 330f), tabOffsets(listOf(100f, 150f, 80f, 60f)))
        assertEquals(emptyList(), tabOffsets(emptyList()))
    }

    @Test
    fun widthsShrinkTogetherOnlyWhenTheyOverflow() {
        assertEquals(listOf(100f, 100f), shrinkTabWidthsToFit(listOf(100f, 100f), 300f))
        val shrunk = shrinkTabWidthsToFit(listOf(300f, 100f), 200f)
        assertEquals(200f, shrunk.sum(), 1e-3f)
        assertEquals(3f, shrunk[0] / shrunk[1], 1e-3f)
        assertEquals(listOf(300f), shrinkTabWidthsToFit(listOf(300f), 0f))
    }

    @Test
    fun fitSplitKeepsTheLastTabsThatFitAndHonoursTheLimit() {
        val ids = listOf("a", "b", "c", "d")
        val widths = listOf(100f, 100f, 100f, 100f)
        // Everything fits.
        assertEquals(ids to emptyList(), splitTabsForVisibilityFit(ids, widths, 400, 40, 10).let { it.first to it.second })
        // 3 tabs (300) + the overflow button (40) fit 350 px; 4 do not.
        assertEquals(listOf("b", "c", "d") to listOf("a"), splitTabsForVisibilityFit(ids, widths, 350, 40, 10))
        // The limit caps it even with room to spare.
        assertEquals(listOf("c", "d") to listOf("a", "b"), splitTabsForVisibilityFit(ids, widths, 1000, 40, 2))
        // At least one tab stays visible however narrow the strip is.
        assertEquals(listOf("d") to listOf("a", "b", "c"), splitTabsForVisibilityFit(ids, widths, 20, 40, 10))
        // Unmeasured strip: show everything.
        assertEquals(ids to emptyList(), splitTabsForVisibilityFit(ids, widths, 0, 40, 10))
        // Differing widths: a wide last tab pushes the earlier ones out.
        assertEquals(listOf("c", "d") to listOf("a", "b"), splitTabsForVisibilityFit(ids, listOf(50f, 50f, 100f, 240f), 400, 40, 10))
    }

    @Test
    fun theTabUnderAPointer() {
        val widths = listOf(100f, 50f, 150f)
        assertEquals(0, tabIndexAtX(0f, widths))
        assertEquals(0, tabIndexAtX(99f, widths))
        assertEquals(1, tabIndexAtX(100f, widths))
        assertEquals(2, tabIndexAtX(160f, widths))
        assertEquals(2, tabIndexAtX(5000f, widths))
        assertEquals(0, tabIndexAtX(-5f, widths))
        assertEquals(0, tabIndexAtX(10f, emptyList()))
    }

    // ── variable-width drag order ────────────────────────────────────────────

    @Test
    fun withEqualWidthsTheVariableDragMathsEqualsTheFixedOne() {
        val ids = listOf("a", "b", "c", "d")
        for (start in ids.indices) {
            for (offset in listOf(-400f, -150f, -60f, -10f, 0f, 10f, 60f, 150f, 400f)) {
                assertEquals(
                    browserTabOrderDuringDrag(ids, ids[start], start, offset, 100f),
                    browserTabOrderDuringDragVariable(ids, List(4) { 100f }, ids[start], start, offset),
                    "start=$start offset=$offset",
                )
            }
        }
    }

    @Test
    fun aWideTabNeedsToTravelFurtherThanANarrowOne() {
        val ids = listOf("a", "b", "c")
        val widths = listOf(100f, 200f, 100f)
        // Dragging the narrow first tab right: its centre (50) + offset + bias must pass b's centre (200).
        assertEquals(ids, browserTabOrderDuringDragVariable(ids, widths, "a", 0, 100f))
        assertEquals(listOf("b", "a", "c"), browserTabOrderDuringDragVariable(ids, widths, "a", 0, 160f))
        // Dragging the wide middle tab left only needs its centre to pass a's centre.
        assertEquals(ids, browserTabOrderDuringDragVariable(ids, widths, "b", 1, -60f))
        assertEquals(listOf("b", "a", "c"), browserTabOrderDuringDragVariable(ids, widths, "b", 1, -120f))
        // Dragging the last tab to the far left puts it first.
        assertEquals(listOf("c", "a", "b"), browserTabOrderDuringDragVariable(ids, widths, "c", 2, -600f))
        // Dragging the first tab far right puts it last.
        assertEquals(listOf("b", "c", "a"), browserTabOrderDuringDragVariable(ids, widths, "a", 0, 900f))
    }

    @Test
    fun theVariableDragMathsIgnoresBadInput() {
        val ids = listOf("a", "b")
        assertEquals(ids, browserTabOrderDuringDragVariable(ids, listOf(100f, 100f), null, 0, 300f))
        assertEquals(ids, browserTabOrderDuringDragVariable(ids, listOf(100f, 100f), "zzz", 0, 300f))
        assertEquals(ids, browserTabOrderDuringDragVariable(ids, listOf(100f), "a", 0, 300f))
        assertEquals(ids, browserTabOrderDuringDragVariable(ids, listOf(100f, 100f), "a", 5, 300f))
        assertEquals(ids, browserTabOrderDuringDragVariable(ids, listOf(0f, 100f), "a", 0, 300f))
    }
}

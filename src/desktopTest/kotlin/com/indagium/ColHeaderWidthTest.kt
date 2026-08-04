package com.indagium

import androidx.compose.ui.unit.dp
import com.indagium.ui.headerPidColumnWidth
import com.indagium.ui.pidFieldColumnWidth
import kotlin.test.Test
import kotlin.test.assertEquals

// Regression coverage for the PID/TID header-vs-row alignment bug (process-names feature): the
// header sized its "PID" box at COL_HEADER_FONT_SP (9sp) while the row renders the pid field
// inline at the row's own configurable content font size (AppSettings.fontSize, threaded into
// ColHeader as contentFontSizeSp), so the two drifted apart at the default font (12sp) and beyond
// — every header after PID (TID/LVL/TAG/MESSAGE) shifted left as a result. headerPidColumnWidth is
// ColHeader's width decision pulled out as a pure function (this project has no Compose test
// harness) so it's directly testable without rendering anything.
class ColHeaderWidthTest {
    // ── headerPidColumnWidth: ColHeader's own "PID" box width decision ─────────────────

    @Test
    fun fiveOrFewerCharsAlwaysYieldsTheExactPreFeatureFortyDpRegardlessOfFontSize() {
        for (fontSize in listOf(8f, 10f, 12f, 16f, 20f, 24f)) {
            for (chars in listOf(1, 2, 3, 4, 5)) {
                assertEquals(40.dp, headerPidColumnWidth(chars, fontSize), "chars=$chars fontSize=$fontSize")
            }
        }
    }

    @Test
    fun aboveFiveCharsDerivesFromTheSuppliedContentFontSizeNotAFixedHeaderFont() {
        // This is the crux of the fix: the header box width must come from the SAME font size the
        // row actually renders the field at. If ColHeader ever reverted to hardcoding
        // COL_HEADER_FONT_SP (9f) here instead of threading the caller's content font size, this
        // test would fail for every content font size other than 9.
        for (fontSize in listOf(8f, 10f, 12f, 14f, 16f, 20f, 24f)) {
            for (chars in listOf(6, 8, 12, 20)) {
                assertEquals(
                    pidFieldColumnWidth(fontSize, chars),
                    headerPidColumnWidth(chars, fontSize),
                    "chars=$chars fontSize=$fontSize",
                )
            }
        }
    }

    @Test
    fun theHeaderBoxWidensProportionallyWithContentFontSizeForAFixedCharCount() {
        // Concrete numbers pinned so a future change to the underlying constant is visible here,
        // not just algebraically identical: at the default 12sp content font, a 12-char pid field
        // (the ProcessNameRenderingTest fixtures' width) reserves more than the pre-feature 40.dp
        // fixed box — which is exactly the bug: the OLD header (COL_HEADER_FONT_SP=9f) reserved
        // only 12 * 9 * 0.65 = 70.2dp while the row at the default font actually needed
        // 12 * 12 * 0.65 = 93.6dp, a ~23dp shortfall that shifted every later header left.
        assertEquals(70.2.dp, pidFieldColumnWidth(9f, 12))
        assertEquals(93.6.dp, headerPidColumnWidth(12, 12f))
    }

    // ── pidFieldColumnWidth: the shared formula itself, used by both the PID and (via the same
    // helper, inlined at its ColHeader call site) TID header boxes ─────────────────────

    @Test
    fun widthScalesLinearlyWithBothCharCountAndFontSize() {
        assertEquals(39.dp, pidFieldColumnWidth(12f, 5))
        assertEquals(78.dp, pidFieldColumnWidth(24f, 5))
        assertEquals(65.dp, pidFieldColumnWidth(20f, 5))
    }

    @Test
    fun tidColumnWidthAtDefaultFontAlreadyExceedsThePreFeatureFixedFortyDp() {
        // TID's row field (entry.tid.toString().padStart(5)) is a genuinely fixed 5-char field, so
        // ColHeader now sizes its "TID" box the same way it sizes "PID" — reusing pidFieldColumnWidth
        // directly with chars=5. Even at the DEFAULT content font size the true field width (39dp)
        // is already close to the old fixed 40dp box, and at any font size above the default it
        // exceeds it — confirming the fixed 40.dp was already latently wrong before this fix, not
        // just at extreme settings.
        assertEquals(39.dp, pidFieldColumnWidth(12f, 5))
        assert(pidFieldColumnWidth(16f, 5) > 40.dp) { "16sp TID field should already exceed the old fixed 40dp box" }
    }
}

package com.indagium.ui

import androidx.compose.ui.graphics.Color
import com.indagium.model.AppSettings
import com.indagium.model.LogTab
import com.indagium.model.TabCloseButtonPosition
import com.indagium.model.TabLabelMode
import com.indagium.model.TabSizeMode
import com.indagium.model.TabStripPosition
import com.indagium.model.TabStripStyle
import java.io.File
import kotlin.math.max

// The pure side of the tab strip's appearance (Settings > Appearance > Tabs): what kind of source a tab shows, its badge and
// source colour, the label parts for each label mode, the strip height per style, and the maths for tabs that are as wide as their
// label (FIT mode): widths, offsets, how many fit, and which tab a pointer is on / where a dragged tab lands when widths differ.
// No Compose UI here (ui/TabStyles.kt draws), so all of it is unit-tested directly (TabAppearanceTest).

/** What a tab shows, for its badge and source colour. */
internal enum class TabKind { LOG, ZIP, IND, CAPTURE, LIVE, DIAGRAM, TESTS, HOME }

private const val IND_EXTENSION = ".ind"

private fun String?.endsWithInd(): Boolean = this?.endsWith(IND_EXTENSION, ignoreCase = true) == true

/**
 * The kind of a log tab, from the fields that already say where it came from: the New tab (home), a running capture
 * ([LogTab.captureSessionId]) or a live-tailed file ([LogTab.tailing]) is LIVE; a `.ind` capture is IND; any other capture
 * (stopped, archive-backed or an AI-test lane) is CAPTURE; an entry of an archive ([LogTab.archiveCandidate] or a `!` in the
 * source path, e.g. a bug-report zip) is ZIP; anything else is a plain LOG file.
 */
internal fun tabKind(tab: LogTab): TabKind = when {
    tab.isCaptureLauncher -> TabKind.HOME
    tab.captureSessionId != null || tab.tailing -> TabKind.LIVE
    tab.captureArchivePath.endsWithInd() || tab.sourcePath.endsWithInd() -> TabKind.IND
    tab.captureArchivePath != null || tab.captureSourceSessionId != null || tab.testLane != null -> TabKind.CAPTURE
    tab.archiveCandidate != null || tab.sourcePath?.contains('!') == true -> TabKind.ZIP
    else -> TabKind.LOG
}

/** The badge text of a kind; LOG, ZIP and IND are the very labels of the Recent files list ([recentBadgeLabel]). */
internal fun tabKindBadge(kind: TabKind): String = when (kind) {
    TabKind.LOG -> "LOG"
    TabKind.ZIP, TabKind.CAPTURE -> "ZIP"
    TabKind.IND -> "IND"
    TabKind.LIVE -> "LIVE"
    TabKind.DIAGRAM -> "UML"
    TabKind.TESTS -> "TEST"
    TabKind.HOME -> "NEW"
}

private val SOURCE_BLUE = Color(0xFF60a5fa)
private val SOURCE_ORANGE = Color(0xFFfb923c)
private val SOURCE_PURPLE = Color(0xFFa78bfa)

/** The colour of a kind's source stripe: file blue, live red, bug-report zip orange, capture purple; diagram and test tabs follow the theme. */
internal fun tabSourceColor(kind: TabKind, tc: ThemeColors): Color = when (kind) {
    TabKind.LOG -> SOURCE_BLUE
    TabKind.ZIP -> SOURCE_ORANGE
    TabKind.IND, TabKind.CAPTURE -> SOURCE_PURPLE
    TabKind.LIVE -> DANGER_RED
    TabKind.DIAGRAM -> PKG_CYAN
    TabKind.TESTS -> tc.success
    TabKind.HOME -> tc.td
}

// ── Labels ───────────────────────────────────────────────────────────

private const val SHORT_ID_LENGTH = 8
private const val LABEL_SEPARATOR = " — "

private fun sourceSuffix(candidate: LogTab, includeArchiveEntryPath: Boolean): String {
    val sourcePath = candidate.sourcePath.orEmpty()
    val archiveSeparator = sourcePath.indexOf('!')
    if (archiveSeparator >= 0) {
        val archiveName = File(sourcePath.substring(0, archiveSeparator)).name.takeIf { it.isNotBlank() }
        val entryPath = sourcePath.substring(archiveSeparator + 1).trim('/').takeIf { it.isNotBlank() }
        if (archiveName != null && includeArchiveEntryPath && entryPath != null) return "$archiveName/$entryPath"
        if (archiveName != null) return archiveName
    }
    return File(sourcePath).parentFile?.name?.takeIf { it.isNotBlank() } ?: candidate.id.take(SHORT_ID_LENGTH)
}

/**
 * What tells two tabs of the same file name apart (the folder, the archive, the archive entry, finally the tab id), or null when
 * [tab]'s file name is unique among [allTabs] and needs no help. This is the second half of [tabDisplayLabel]'s `name — suffix`.
 */
internal fun tabDisambiguation(tab: LogTab, allTabs: List<LogTab>): String? {
    val sameNameTabs = allTabs.filter { it.filename == tab.filename }
    if (sameNameTabs.size < 2) return null
    val compactSuffix = sourceSuffix(tab, includeArchiveEntryPath = false)
    if (sameNameTabs.count { sourceSuffix(it, includeArchiveEntryPath = false) == compactSuffix } == 1) return compactSuffix
    val expandedSuffix = sourceSuffix(tab, includeArchiveEntryPath = true)
    if (sameNameTabs.count { sourceSuffix(it, includeArchiveEntryPath = true) == expandedSuffix } == 1) return expandedSuffix
    return "$expandedSuffix$LABEL_SEPARATOR${tab.id.take(SHORT_ID_LENGTH)}"
}

// The name of the folder or archive [tab]'s file lives in, or null when the tab has no source path (or the path has no folder part).
private fun tabContainerName(tab: LogTab): String? {
    val sourcePath = tab.sourcePath?.takeIf { it.isNotBlank() } ?: return null
    val archiveSeparator = sourcePath.indexOf('!')
    val container = if (archiveSeparator >= 0) File(sourcePath.substring(0, archiveSeparator)) else File(sourcePath).parentFile
    return container?.name?.takeIf { it.isNotBlank() }
}

/** The folder or archive a tab's file lives in (what a capture session or a bug report is), or the file name when the tab has no source path. */
private fun tabSessionName(tab: LogTab, allTabs: List<LogTab>): String =
    tabDisambiguation(tab, allTabs) ?: tabContainerName(tab) ?: tab.filename

/** A tab's label: [primary] and, when the label has a second part, [secondary] (shown after a dash). */
internal data class TabLabelParts(val primary: String, val secondary: String? = null) {
    /** The whole label on one line. */
    fun joined(): String = if (secondary == null) primary else "$primary$LABEL_SEPARATOR$secondary"
}

/**
 * The label parts of [tab] for [mode]: FILE_SESSION (the default) is the file name, with the folder or archive (see
 * [tabDisambiguation]) appended only when another open tab has the same file name, so `joined()` is exactly [tabDisplayLabel];
 * SESSION_ONLY the folder/archive the file lives in; FILE_ONLY the bare file name; FILE_FOLDER_ALWAYS `file — folder` for every tab
 * (the disambiguation wins where it is more specific than the folder, so two tabs never get the same label; a tab with no source
 * path and a unique name is just the file name).
 */
internal fun tabLabelParts(tab: LogTab, allTabs: List<LogTab>, mode: TabLabelMode): TabLabelParts = when (mode) {
    TabLabelMode.FILE_SESSION -> TabLabelParts(tab.filename, tabDisambiguation(tab, allTabs))
    TabLabelMode.SESSION_ONLY -> TabLabelParts(tabSessionName(tab, allTabs))
    TabLabelMode.FILE_ONLY -> TabLabelParts(tab.filename)
    TabLabelMode.FILE_FOLDER_ALWAYS -> TabLabelParts(tab.filename, tabDisambiguation(tab, allTabs) ?: tabContainerName(tab))
}

/**
 * The dim second line of a two-line tab: the file name when the first line is the session only, then the row count and the
 * live / capture state.
 */
internal fun tabDetailLine(tab: LogTab, kind: TabKind, mode: TabLabelMode): String = buildList {
    if (mode == TabLabelMode.SESSION_ONLY) add(tab.filename)
    if (!tab.isCaptureLauncher) add(if (tab.logData.size == 1) "1 line" else "${tab.logData.size} lines")
    when (kind) {
        TabKind.LIVE -> add(if (tab.captureSessionId != null) "capturing" else "tailing")
        TabKind.CAPTURE, TabKind.IND -> add("capture")
        TabKind.ZIP -> add("archive")
        else -> Unit
    }
}.joinToString(" · ")

// ── Strip metrics ────────────────────────────────────────────────────

internal const val TAB_STRIP_HEIGHT_DP: Int = 36
internal const val TAB_STRIP_COMPACT_HEIGHT_DP: Int = 28
internal const val TAB_STRIP_TWO_LINE_HEIGHT_DP: Int = 44

/** The tab strip's height in dp for a style: COMPACT 28, TWO_LINE 44, every other style the long-standing 36. */
internal fun tabStripHeightDp(style: TabStripStyle): Int = when (style) {
    TabStripStyle.COMPACT -> TAB_STRIP_COMPACT_HEIGHT_DP
    TabStripStyle.TWO_LINE -> TAB_STRIP_TWO_LINE_HEIGHT_DP
    else -> TAB_STRIP_HEIGHT_DP
}

/** True when a tab of [style] draws a file-type badge: the TYPED style always does, the others when the setting says so. */
internal fun tabBadgeShown(settings: AppSettings): Boolean = settings.tabShowTypeBadge || settings.tabStripStyle == TabStripStyle.TYPED

/** True when a tab of [style] draws the source stripe: the SOURCE_STRIPE style always does, the others when the setting says so. */
internal fun tabStripeShown(settings: AppSettings): Boolean = settings.tabShowSourceStripe || settings.tabStripStyle == TabStripStyle.SOURCE_STRIPE

/**
 * Everything the tab shell needs to draw one tab beyond the label: the strip style, the kind (for badge and stripe), whether the
 * badge/stripe/close button show, where that button sits, the dim second line and whether the tab is live-tailing (only for width
 * estimates; the dot itself is the shell's `leading` slot). [position] is the window edge the strip sits on: the active tab's accent
 * mark and a raised card face the content side of it. [Default] is the long-standing look and draws exactly what the shell drew before
 * styles existed.
 */
internal data class TabAppearance(
    val style: TabStripStyle = TabStripStyle.CURRENT,
    val kind: TabKind = TabKind.LOG,
    val showBadge: Boolean = false,
    val showStripe: Boolean = false,
    val closeAlways: Boolean = true,
    val secondLine: String? = null,
    val tailing: Boolean = false,
    val position: TabStripPosition = TabStripPosition.TOP,
    val closeButtonPosition: TabCloseButtonPosition = TabCloseButtonPosition.RIGHT,
    /** The colour of the group this tab belongs to: it tints the tab's own shape, under its content. Null for an ungrouped tab. */
    val groupColor: Color? = null,
    /** How strongly [groupColor] tints the tab (raised while a dragged tab hovers the group). */
    val groupTintAlpha: Float = 0f,
    /** A 1 dp outline in this colour around the tab's own shape, over its chrome (a group chip's border). Null draws none. */
    val outlineColor: Color? = null,
) {
    companion object {
        val Default = TabAppearance()
    }
}

/** A tab's label and appearance, computed once per frame for the strip. */
internal data class TabDescriptor(val label: String, val appearance: TabAppearance)

private fun appearanceOf(settings: AppSettings, kind: TabKind, secondLine: String? = null, tailing: Boolean = false) = TabAppearance(
    style = settings.tabStripStyle,
    kind = kind,
    showBadge = tabBadgeShown(settings),
    showStripe = tabStripeShown(settings),
    closeAlways = settings.tabCloseOnEvery,
    secondLine = secondLine,
    tailing = tailing,
    position = settings.tabStripPosition,
    closeButtonPosition = settings.tabCloseButtonPosition,
)

internal fun logTabDescriptor(tab: LogTab, allTabs: List<LogTab>, settings: AppSettings): TabDescriptor {
    val kind = tabKind(tab)
    val mode = settings.tabLabelMode
    val second = if (settings.tabStripStyle == TabStripStyle.TWO_LINE) tabDetailLine(tab, kind, mode) else null
    return TabDescriptor(tabLabelParts(tab, allTabs, mode).joined(), appearanceOf(settings, kind, second, tab.tailing))
}

internal fun workspaceTabDescriptor(label: String, kind: TabKind, settings: AppSettings): TabDescriptor =
    TabDescriptor(label, appearanceOf(settings, kind))

// ── FIT-width tabs ───────────────────────────────────────────────────

internal const val FIT_TAB_MIN_DP: Float = 72f
internal const val FIT_TAB_MAX_DP: Float = 240f
private const val MONO_CHAR_DP = 7.2f
private const val MONO_COMPACT_CHAR_DP = 6.6f
private const val UI_CHAR_DP = 6.6f
private const val TAB_PADDING_DP = 18f
private const val CLOSE_BUTTON_DP = 24f
private const val TAB_GAP_DP = 7f
private const val BADGE_DP = 32f
private const val DOT_DP = 14f
private const val SEGMENT_DOT_DP = 12f

/** The natural width in dp of a tab with [label]: its padding, the label (estimated from the character count), badge, dot and close button. */
internal fun tabFitContentDp(label: String, appearance: TabAppearance): Float {
    val charDp = when (appearance.style) {
        TabStripStyle.COMPACT -> MONO_COMPACT_CHAR_DP
        TabStripStyle.SEGMENTED, TabStripStyle.QUIET, TabStripStyle.OUTLINED, TabStripStyle.PILL -> UI_CHAR_DP
        else -> MONO_CHAR_DP
    }
    val longest = max(label.length, appearance.secondLine?.length ?: 0)
    var width = TAB_PADDING_DP + longest * charDp + CLOSE_BUTTON_DP + TAB_GAP_DP
    if (appearance.showBadge) width += BADGE_DP + TAB_GAP_DP
    if (appearance.tailing) width += DOT_DP + TAB_GAP_DP
    if (appearance.style == TabStripStyle.SEGMENTED) width += SEGMENT_DOT_DP
    return width
}

/** Each natural width (dp) clamped to [minDp]..[maxDp] and converted to pixels. */
internal fun fitTabWidthsPx(
    contentWidthsDp: List<Float>,
    density: Float,
    minDp: Float = FIT_TAB_MIN_DP,
    maxDp: Float = FIT_TAB_MAX_DP,
): List<Float> = contentWidthsDp.map { it.coerceIn(minDp, maxDp) * density }

/** The left edge of every tab when [widths] are laid out one after another from 0. */
internal fun tabOffsets(widths: List<Float>): List<Float> {
    var x = 0f
    return widths.map { w -> x.also { x += w } }
}

/** [widths] scaled down evenly when they add up to more than [availablePx] (one over-long label on a narrow strip); otherwise unchanged. */
internal fun shrinkTabWidthsToFit(widths: List<Float>, availablePx: Float): List<Float> {
    val total = widths.sum()
    return if (availablePx <= 0f || total <= availablePx) widths else widths.map { it * availablePx / total }
}

/**
 * FIT-mode counterpart of [splitTabsForVisibility]: shows the last tabs whose [widthsPx] (parallel to [tabs]) add up to the container,
 * leaving room for the overflow button when some tabs hide, honouring [visibleTabLimit]; always at least one tab visible.
 */
internal fun <T> splitTabsForVisibilityFit(
    tabs: List<T>,
    widthsPx: List<Float>,
    containerPx: Int,
    overflowButtonPx: Int,
    visibleTabLimit: Int,
): Pair<List<T>, List<T>> {
    if (containerPx == 0) return tabs to emptyList()
    if (tabs.isEmpty()) return emptyList<T>() to emptyList()
    var n = minOf(tabs.size, visibleTabLimit.coerceIn(1, tabs.size))
    while (n > 1) {
        val avail = if (n < tabs.size) containerPx - overflowButtonPx else containerPx
        if (widthsPx.takeLast(n).sum() <= avail) break
        n--
    }
    return tabs.takeLast(n) to tabs.dropLast(n)
}

/** The index of the tab under [x] when [widths] are laid out from 0; past the last tab it is the last one, before the first the first. */
internal fun tabIndexAtX(x: Float, widths: List<Float>): Int {
    if (widths.isEmpty()) return 0
    var edge = 0f
    widths.forEachIndexed { i, w ->
        edge += w
        if (x < edge) return i
    }
    return widths.lastIndex
}

/**
 * [browserTabOrderDuringDrag] for tabs of different widths: [widthsPx] is parallel to [visibleIds]. The dragged tab's centre (plus the
 * same quarter-width snap bias in the direction of travel) is compared with the other tabs' resting centres, so with equal widths
 * the result is identical to the fixed-width maths.
 */
internal fun browserTabOrderDuringDragVariable(
    visibleIds: List<String>,
    widthsPx: List<Float>,
    draggedId: String?,
    dragStartIndex: Int,
    dragOffsetX: Float,
): List<String> {
    val dragged = draggedId?.takeIf { it in visibleIds } ?: return visibleIds
    if (widthsPx.size != visibleIds.size || dragStartIndex !in visibleIds.indices) return visibleIds
    val draggedWidth = widthsPx[dragStartIndex]
    if (draggedWidth <= 0f) return visibleIds
    val offsets = tabOffsets(widthsPx)
    val bias = draggedWidth * TAB_DRAG_SNAP_BIAS * dragOffsetX.compareTo(0f)
    val draggedCenter = offsets[dragStartIndex] + draggedWidth / 2f + dragOffsetX + bias
    val without = visibleIds.filter { it != dragged }
    val insertAt = without.indexOfFirst { id ->
        val i = visibleIds.indexOf(id)
        draggedCenter < offsets[i] + widthsPx[i] / 2f
    }.takeIf { it >= 0 } ?: without.size
    return without.take(insertAt) + dragged + without.drop(insertAt)
}

/** Whether the strip lays tabs out by their label width. */
internal fun AppSettings.tabsFitLabel(): Boolean = tabSizeMode == TabSizeMode.FIT

package com.indagium.ui

import com.indagium.model.AppSettings
import com.indagium.model.TileAxis
import com.indagium.model.TileLeaf
import com.indagium.model.TileNode
import com.indagium.model.TileSplit

/** Stable keys persisted for the seven native Tests workspace tiles. */
internal enum class TestsWorkspaceTileId(val key: String) {
    SUITES("suites"),
    LIBRARY("library"),
    ACTIVITY("activity"),
    WORKSPACE("workspace"),
    EXECUTION("execution"),
    DETAILS("details"),
    EVIDENCE("evidence"),
    ;

    companion object {
        fun fromKey(key: String?): TestsWorkspaceTileId? = entries.firstOrNull { it.key == key }
    }
}

internal typealias TestsWorkspaceLayout = TileNode<TestsWorkspaceTileId>

internal const val TESTS_WORKSPACE_STANDARD_NAV_WIDTH_DP = 260f
internal const val TESTS_WORKSPACE_STANDARD_DETAILS_WIDTH_DP = 360f
private const val TESTS_WORKSPACE_FOCUS_NAV_WIDTH_DP = 210f
private const val TESTS_WORKSPACE_FOCUS_DETAILS_WIDTH_DP = 300f
private const val TESTS_WORKSPACE_SUITE_TILE_WEIGHT = 1.8f
private const val TESTS_WORKSPACE_MAIN_TILE_WEIGHT = 2.2f
private const val TESTS_WORKSPACE_FOCUS_MAIN_TILE_WEIGHT = 3f
private const val TESTS_WORKSPACE_FOCUS_EXECUTION_WEIGHT = 0.8f
private const val TESTS_WORKSPACE_DETAILS_TILE_WEIGHT = 1.25f
private const val TESTS_WORKSPACE_FOCUS_DETAILS_TILE_WEIGHT = 1.4f

/** The layout settings the JSON codec stores under AppSettings.testsWorkspaceLayout. */
internal data class TestsWorkspaceLayoutState(
    val layout: TestsWorkspaceLayout,
    val hidden: List<TestsWorkspaceTileId> = emptyList(),
    val collapsed: List<TestsWorkspaceTileId> = emptyList(),
    val locked: Boolean = false,
)

private fun leaf(id: TestsWorkspaceTileId, weight: Float = 1f) = TileLeaf(id, weight)

private fun column(vararg children: TileNode<TestsWorkspaceTileId>, widthDp: Float? = null) =
    TileSplit(TileAxis.COLUMN, children.toList(), sizeDp = widthDp)

/** The normal layout keeps navigation together on the left, authoring and execution in the middle, and context on the right. */
internal fun testsWorkspaceDefaultLayout(): TestsWorkspaceLayout = TileSplit(
    TileAxis.ROW,
    listOf(
        column(
            leaf(TestsWorkspaceTileId.SUITES, TESTS_WORKSPACE_SUITE_TILE_WEIGHT),
            leaf(TestsWorkspaceTileId.LIBRARY),
            leaf(TestsWorkspaceTileId.ACTIVITY),
            widthDp = TESTS_WORKSPACE_STANDARD_NAV_WIDTH_DP,
        ),
        column(leaf(TestsWorkspaceTileId.WORKSPACE, TESTS_WORKSPACE_MAIN_TILE_WEIGHT), leaf(TestsWorkspaceTileId.EXECUTION)),
        column(
            leaf(TestsWorkspaceTileId.DETAILS, TESTS_WORKSPACE_DETAILS_TILE_WEIGHT),
            leaf(TestsWorkspaceTileId.EVIDENCE),
            widthDp = TESTS_WORKSPACE_STANDARD_DETAILS_WIDTH_DP,
        ),
    ),
)

internal fun testsWorkspaceFocusLayout(): TestsWorkspaceLayout = TileSplit(
    TileAxis.ROW,
    listOf(
        column(
            leaf(TestsWorkspaceTileId.SUITES, TESTS_WORKSPACE_SUITE_TILE_WEIGHT),
            leaf(TestsWorkspaceTileId.LIBRARY),
            leaf(TestsWorkspaceTileId.ACTIVITY),
            widthDp = TESTS_WORKSPACE_FOCUS_NAV_WIDTH_DP,
        ),
        column(
            leaf(TestsWorkspaceTileId.WORKSPACE, TESTS_WORKSPACE_FOCUS_MAIN_TILE_WEIGHT),
            leaf(TestsWorkspaceTileId.EXECUTION, TESTS_WORKSPACE_FOCUS_EXECUTION_WEIGHT),
        ),
        column(
            leaf(TestsWorkspaceTileId.DETAILS, TESTS_WORKSPACE_FOCUS_DETAILS_TILE_WEIGHT),
            leaf(TestsWorkspaceTileId.EVIDENCE),
            widthDp = TESTS_WORKSPACE_FOCUS_DETAILS_WIDTH_DP,
        ),
    ),
)

/** A compact-width projection: navigation stays in an independent left column; the four work panels stack on the right. */
internal fun testsWorkspaceCompactLayout(layout: TestsWorkspaceLayout): TestsWorkspaceLayout {
    val leaves = mutableMapOf<TestsWorkspaceTileId, TileLeaf<TestsWorkspaceTileId>>()

    fun collect(node: TileNode<TestsWorkspaceTileId>) {
        when (node) {
            is TileLeaf -> leaves.putIfAbsent(node.section, node)
            is TileSplit -> node.children.forEach(::collect)
        }
    }
    collect(layout)
    val order = tileLeafSections(layout)
    val nav = order.filter { it in TESTS_WORKSPACE_NAV_TILES }
    val work = order.filterNot { it in TESTS_WORKSPACE_NAV_TILES }
    val compactRoot = layout as? TileSplit
    val compactStored = compactRoot?.axis == TileAxis.ROW && compactRoot.children.size == 2 &&
        compactRoot.children.firstOrNull()?.let { child ->
            child is TileSplit && child.axis == TileAxis.COLUMN && tileLeafSections(child).all { it in TESTS_WORKSPACE_NAV_TILES }
        } == true
    val storedNavWidth = compactRoot?.children?.firstOrNull()?.sizeDp
        ?: (layout as? TileSplit)?.children?.firstOrNull { child ->
            child is TileSplit && child.axis == TileAxis.COLUMN && tileLeafSections(child).all { it in TESTS_WORKSPACE_NAV_TILES }
        }?.sizeDp
        ?: TESTS_WORKSPACE_STANDARD_NAV_WIDTH_DP

    fun group(ids: List<TestsWorkspaceTileId>, widthDp: Float? = null): TileNode<TestsWorkspaceTileId> {
        val children = ids.map { id -> leaves[id]?.let { it.copy(sizeDp = if (compactStored) it.sizeDp else null) } ?: TileLeaf(id) }
        return TileSplit(TileAxis.COLUMN, children, sizeDp = widthDp)
    }
    return TileSplit(
        TileAxis.ROW,
        listOf(group(nav, widthDp = storedNavWidth), group(work)),
    )
}

internal enum class TestsWorkspacePreset(val label: String, val layout: TestsWorkspaceLayout) {
    STANDARD("Standard", testsWorkspaceDefaultLayout()),
    WORKSPACE_FOCUS("Workspace focus", testsWorkspaceFocusLayout()),
}

internal val TESTS_WORKSPACE_LONE_COLUMNS = TileLoneColumns<TestsWorkspaceTileId>(
    tiles = { true },
    scrolls = { true },
)

internal val TESTS_WORKSPACE_NAV_TILES = setOf(
    TestsWorkspaceTileId.SUITES,
    TestsWorkspaceTileId.LIBRARY,
    TestsWorkspaceTileId.ACTIVITY,
)

/** Restores every stable tile once, drops unknown state and always keeps the Workspace reachable. */
internal fun sanitizeTestsWorkspaceLayoutState(
    layout: TestsWorkspaceLayout?,
    hidden: List<TestsWorkspaceTileId>,
    collapsed: List<TestsWorkspaceTileId>,
    locked: Boolean,
): TestsWorkspaceLayoutState {
    val seen = mutableSetOf<TestsWorkspaceTileId>()

    fun unique(node: TileNode<TestsWorkspaceTileId>): TileNode<TestsWorkspaceTileId>? = when (node) {
        is TileLeaf -> if (seen.add(node.section)) node else null
        is TileSplit -> {
            val children = node.children.mapNotNull(::unique)
            when (children.size) {
                0 -> null
                1 -> children.single()
                else -> node.copy(children = children)
            }
        }
    }

    val tree = unique(layout ?: testsWorkspaceDefaultLayout()) ?: testsWorkspaceDefaultLayout().also {
        seen.addAll(TestsWorkspaceTileId.entries)
    }
    val completed = TestsWorkspaceTileId.entries
        .filterNot(seen::contains)
        .fold(tree) { acc, id -> appendTile(acc, id, TESTS_WORKSPACE_LONE_COLUMNS) }
    val normalized = normalizeTileLayout(completed, TESTS_WORKSPACE_LONE_COLUMNS) ?: testsWorkspaceDefaultLayout()
    val validHidden = hidden.distinct().filter { it in TestsWorkspaceTileId.entries }
    val safeHidden = validHidden - TestsWorkspaceTileId.WORKSPACE
    val validCollapsed = collapsed.distinct().filter { it in TestsWorkspaceTileId.entries }
    return TestsWorkspaceLayoutState(normalized, safeHidden, validCollapsed, locked)
}

internal fun testsWorkspaceDefaultState(): TestsWorkspaceLayoutState =
    sanitizeTestsWorkspaceLayoutState(testsWorkspaceDefaultLayout(), emptyList(), emptyList(), locked = false)

internal fun testsWorkspaceVisibleTiles(state: TestsWorkspaceLayoutState): Set<TestsWorkspaceTileId> =
    tileLeafSections(state.layout).filterNotTo(LinkedHashSet()) { it in state.hidden }

internal fun testsWorkspacePresetActive(state: TestsWorkspaceLayoutState, preset: TestsWorkspacePreset): Boolean =
    state.layout == preset.layout && state.hidden.isEmpty() && state.collapsed.isEmpty() && !state.locked

/** A preset resets the saved arrangement and panel flags together. */
internal fun applyTestsWorkspacePreset(preset: TestsWorkspacePreset): TestsWorkspaceLayoutState =
    sanitizeTestsWorkspaceLayoutState(preset.layout, emptyList(), emptyList(), locked = false)

internal fun TestsWorkspaceLayoutState.withTileVisible(id: TestsWorkspaceTileId, visible: Boolean): TestsWorkspaceLayoutState {
    val shown = testsWorkspaceVisibleTiles(this)
    if (visible == (id in shown)) return this
    if (!visible && (id == TestsWorkspaceTileId.WORKSPACE || shown.size <= 1)) return this
    return copy(hidden = if (visible) hidden - id else (hidden + id).distinct())
}

internal fun TestsWorkspaceLayoutState.withTileCollapsed(id: TestsWorkspaceTileId, collapsed: Boolean): TestsWorkspaceLayoutState =
    copy(collapsed = if (collapsed) (this.collapsed + id).distinct() else this.collapsed - id)

internal fun AppSettings.currentTestsWorkspaceLayout(): TestsWorkspaceLayoutState =
    testsWorkspaceLayoutFromSettings()?.let {
        sanitizeTestsWorkspaceLayoutState(it.layout, it.hidden, it.collapsed, it.locked)
    } ?: testsWorkspaceDefaultState()

/** Geometry constants parallel the log view: shared 32dp headers, 8dp / 6dp gaps and invisible splitters. */
internal val TESTS_WORKSPACE_CHROME_SPEC = TileChromeSpec<TestsWorkspaceTileId>(
    headerDp = LOG_VIEW_TILE_HEADER_DP,
    titleSp = 12f,
    gapRowDp = LOG_VIEW_GAP_ROW_DP,
    gapColumnDp = LOG_VIEW_GAP_COLUMN_DP,
    cornerDp = 8,
    minTileDp = 140f,
    minPairShare = 0.12f,
    tagPrefix = "tests-tile",
    keyOf = { it.key },
    scrollMinDp = 96f,
    scrollMaxDp = 2000f,
    scrolls = { true },
    loneColumn = { true },
    scrollEdgeRoomDp = LOG_VIEW_HOST_PAD_DP.toFloat(),
    scrollEndHandle = true,
    scrollEndHandleOuterRoomDp = LOG_VIEW_HOST_PAD_DP.toFloat(),
)

internal fun testsWorkspaceTileTitle(id: TestsWorkspaceTileId): String = when (id) {
    TestsWorkspaceTileId.SUITES -> "Test Suites"
    TestsWorkspaceTileId.LIBRARY -> "Test Library"
    TestsWorkspaceTileId.ACTIVITY -> "Activity"
    TestsWorkspaceTileId.WORKSPACE -> "Workspace"
    TestsWorkspaceTileId.EXECUTION -> "Execution & recording"
    TestsWorkspaceTileId.DETAILS -> "Details"
    TestsWorkspaceTileId.EVIDENCE -> "Evidence"
}

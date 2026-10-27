@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class, androidx.compose.foundation.ExperimentalFoundationApi::class)

package com.indagium.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.DisableSelection
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.indagium.testing.model.TestLibrary
import com.indagium.testing.model.TestSuite
import com.indagium.testing.store.StoreResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.awt.FileDialog
import java.awt.Frame
import java.io.File

// The singleton Tests surface routes real authoring, library and report screens into the shared tile shell.
// Navigation is split into Test Suites, Test Library and Activity; Workspace actions stay above its scrolling body.
// Details and Evidence follow selection, while the execution tile retains independently owned operation targets.
// Below 600dp the navigation opens as an overlay. Library/store failures remain explicit in the workspace banners.

private val NAV_ITEM_SHAPE = RoundedCornerShape(8.dp)
private val NAV_ITEM_MIN_HEIGHT = 28.dp
private val NAV_CASE_START_PADDING = 30.dp
private val NAV_CARET_SIZE = 16.dp
private val NAV_GRIP_SLOT = 18.dp
private val NAV_SECTION_MIN_HEIGHT = 32.dp
private val NAV_OVERLAY_MAX_WIDTH = 280.dp
private const val NAV_OVERLAY_WIDTH_FRACTION = 0.86f
private const val NAV_SCRIM_ALPHA = 0.6f
private const val NAV_ITEM_FONT_SP = 12
private const val NAV_SMALL_FONT_SP = 10
internal val TESTS_CONTENT_MAX_WIDTH = 940.dp
private val USAGE_METER_GAP = 5.dp
private val USAGE_BAR_HEIGHT = 4.dp
private const val USAGE_TRACK_ALPHA = .7f
private const val NEW_SUITE_NAME = "New suite"
private const val EXPORT_NAME_MAX_CHARS = 60

@Composable
internal fun TestsWorkspace(state: AppState) {
    val tc = tc()
    val view = state.testsView
    val rootFocus = remember { FocusRequester() }
    val scope = rememberCoroutineScope()
    val ui = remember(state) { TestsUi(state, view, rootFocus, scope) }
    val edition by state.editionService.current.collectAsState()
    val library = state.testLibrary
    val limits = remember(library, edition) { testsLimitsUiState(library, edition.limits) }
    LaunchedEffect(Unit) { ui.reclaimFocus() }
    // Draft-row selection has its own state. Selecting any ordinary item returns the context tiles to that item,
    // while the AppState-owned recording and its apply target remain untouched.
    val selection = listOf(
        view.nav, view.selectedSuiteId, view.selectedCaseId, view.previewCaseId, view.selectedStepId,
        view.caseInspectorField, view.selectedScriptId, view.selectedSharedStepId, view.selectedExampleKey,
        view.selectedRunId, view.selectedIssueId,
    )
    var previousSelection by remember { mutableStateOf(selection) }
    LaunchedEffect(selection) {
        if (selection != previousSelection) {
            view.recordingReviewOpen = false
            previousSelection = selection
        }
    }
    CompositionLocalProvider(LocalTestsUi provides ui, LocalTestsLimits provides limits, LocalTextInputFocusTracker provides ui.textFocus) {
        Column(
            Modifier.fillMaxSize().background(tc.bg)
                // Bare keys (arrows, Esc) go to the current screen, but never while a text input has focus (see TestsUi.routeKey).
                .onPreviewKeyEvent { ui.routeKey(it) }
                .onKeyEvent { event ->
                    if (
                        event.type == KeyEventType.KeyDown && event.key == Key.Escape &&
                        !ui.textFocus.anyFocused && ui.view.maximizedTile != null
                    ) {
                        ui.view.maximizedTile = null
                        ui.reclaimFocus()
                        true
                    } else {
                        false
                    }
                }
                .focusRequester(rootFocus).focusable(),
        ) {
            ui.banner?.let { TestsBannerView(it) { ui.banner = null } }
            if (library.readOnly) TestsLockedNotice(LIBRARY_READ_ONLY_MESSAGE, Modifier.padding(horizontal = 12.dp, vertical = 6.dp))
            state.testLibraryLoadIssues.forEach { issue ->
                TestsBannerView(TestsBanner(issue, isError = true), dismissible = false) { }
            }
            state.testLibraryPersistError?.let { TestsBannerView(TestsBanner(it, isError = true), dismissible = false) { } }
            state.testStorageStatus?.let { TestsBannerView(TestsBanner(it, isError = false), dismissible = false) { } }
            TestsOperationDialogs()
            view.issueDialog?.let { target -> IssueDraftDialog(target) { view.issueDialog = null } }
            var workspaceWidthPx by remember { mutableStateOf(0) }
            val workspaceWidthDp = with(LocalDensity.current) { workspaceWidthPx.toDp().value }
            Box(Modifier.weight(1f).fillMaxWidth().onSizeChanged { workspaceWidthPx = it.width }) {
                if (testsNavCollapsed(workspaceWidthDp)) {
                    TestsNarrowLayout(workspaceWidthDp.dp)
                } else {
                    CompositionLocalProvider(
                        LocalTestsCompactWorkspace provides (workspaceWidthDp < TESTS_WORKSPACE_COMPACT_NAV_BELOW_DP),
                    ) {
                        TestsCenter(Modifier.fillMaxSize())
                    }
                }
            }
        }
    }
}

/** The collapsed layout: a ☰ bar above the centre screen; the nav tree opens over the screen and closes on a choice. */
@Composable
private fun TestsNarrowLayout(totalWidth: Dp) {
    val tc = tc()
    val ui = LocalTestsUi.current
    var navOpen by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().background(tc.p).padding(horizontal = 8.dp, vertical = 4.dp)) {
            TestsIconButton("☰", "Show navigation", onClick = {
                navOpen = !navOpen
                ui.reclaimFocus()
            })
        }
        Divider()
        Box(Modifier.weight(1f).fillMaxWidth()) {
            CompositionLocalProvider(
                LocalTestsCompactWorkspace provides true,
                LocalTestsNavigationOverlay provides true,
            ) {
                TestsCenter(Modifier.fillMaxSize())
            }
            if (navOpen) {
                Row(Modifier.fillMaxSize()) {
                    TestsNavPane(
                        Modifier.width(minOf(totalWidth * NAV_OVERLAY_WIDTH_FRACTION, NAV_OVERLAY_MAX_WIDTH, ui.view.navWidthDp.dp))
                            .fillMaxHeight().testTag("tests-navigation-overlay"),
                        onNavigate = { navOpen = false },
                    )
                    Box(
                        Modifier.weight(1f).fillMaxHeight().background(tc.bg.copy(alpha = NAV_SCRIM_ALPHA)).clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                        ) {
                            navOpen = false
                            ui.reclaimFocus()
                        },
                    )
                }
            }
        }
    }
}

// ── Centre routing ───────────────────────────────────────────────────

/** The suite the workspace is on: the selected one, else the first (selection is only written by user actions). */
internal fun TestLibrary.selectedSuiteOrFirst(selectedId: String?): TestSuite? = selectedId?.let { suite(it) } ?: suites.firstOrNull()

@Composable
private fun TestsCenter(modifier: Modifier) {
    val ui = LocalTestsUi.current
    val view = ui.view
    val library = ui.library
    Box(modifier) {
        when (view.nav) {
            TestsNav.Suites -> {
                val suite = library.selectedSuiteOrFirst(view.selectedSuiteId)
                val case = suite?.cases?.firstOrNull { it.id == view.selectedCaseId }
                when {
                    suite == null -> TestsNoSuites()
                    case != null -> key(suite.id, case.id) { TestsCaseScreen(suite.id, case.id) }
                    else -> key(suite.id) { TestsSuiteScreen(suite.id) }
                }
            }
            TestsNav.SharedSteps -> TestsSharedStepsScreen()
            TestsNav.Scripts -> TestsScriptsScreen()
            TestsNav.Runs -> TestsRunsScreen()
            TestsNav.Issues -> TestsIssuesScreen()
            TestsNav.AgentExamples -> TestsAgentExamplesScreen()
        }
    }
}

@Composable
private fun TestsNoSuites() {
    val ui = LocalTestsUi.current
    val limits = LocalTestsLimits.current
    TestsScreenScaffold {
        TestsHint("No test suites yet. A suite groups the cases an AI agent runs against your app.")
        Spacer(Modifier.height(10.dp))
        HintedButton(
            "New suite",
            onClick = { createSuiteAndSelect(ui) },
            enabled = limits.canCreateSuite && !ui.library.readOnly,
            disabledHint = limits.hint,
            variant = ButtonVariant.Primary,
        )
    }
}

/**
 * The common frame of a centre screen: [header] actions stay above the scrolling [content], while the optional [inspector]
 * and [evidence] occupy the Details and Evidence tiles.
 */
@Composable
internal fun TestsScreenScaffold(
    modifier: Modifier = Modifier,
    header: (@Composable RowScope.() -> Unit)? = null,
    inspector: (@Composable () -> Unit)? = null,
    evidence: (@Composable () -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    TestsSplitScreen(
        main = { Column(Modifier.fillMaxWidth().widthIn(max = TESTS_CONTENT_MAX_WIDTH).padding(16.dp)) { content() } },
        header = header,
        inspector = inspector,
        evidence = evidence,
        modifier = modifier,
    )
}

// ── Navigation pane ──────────────────────────────────────────────────

/** [onNavigate] runs after the user picked a destination (the collapsed layout closes its overlay then). */
@Composable
private fun TestsNavPane(modifier: Modifier, onNavigate: () -> Unit = {}) {
    val tc = tc()
    val ui = LocalTestsUi.current
    val limits = LocalTestsLimits.current
    val view = ui.view
    val library = ui.library
    val edition by ui.state.editionService.current.collectAsState()
    val searching = view.search.isNotBlank()
    val tree = remember(library.suites, view.search) { filterNavTree(library.suites, view.search) }
    val selectedSuite = library.selectedSuiteOrFirst(view.selectedSuiteId)
    val activeCaseId = selectedSuite?.cases?.firstOrNull { it.id == view.selectedCaseId }?.id
    // The selected suite opens itself when it becomes the selection (a click, a new suite, a recording's Review, an import).
    LaunchedEffect(selectedSuite?.id) { selectedSuite?.id?.let(view::expandSuite) }
    val issueRevision by ui.state.issueStore.revision.collectAsState()
    val issueCount by produceState<Int?>(null, issueRevision) { value = withContext(Dispatchers.IO) { ui.state.issueStore.list().size } }
    val runCount = ui.state.testRuns.size
    Column(modifier.background(tc.p)) {
        TestsScrollColumn(Modifier.weight(1f), contentModifier = Modifier.padding(horizontal = 8.dp, vertical = 10.dp)) {
            InlineField(
                value = view.search,
                onValue = { view.search = it },
                placeholder = "Search suites and cases…",
                modifier = Modifier.fillMaxWidth(),
                fontSize = NAV_ITEM_FONT_SP.sp,
                onClear = { view.search = "" },
            )
            NavSectionHeader("Suites", count = library.suites.size, big = true) {
                TestsIconButton(
                    "+",
                    "New suite",
                    onClick = {
                        createSuiteAndSelect(ui)
                        ui.reclaimFocus()
                        onNavigate()
                    },
                    enabled = limits.canCreateSuite && !library.readOnly,
                    disabledHint = if (library.readOnly) LIBRARY_READ_ONLY_MESSAGE else limits.hint,
                )
                TestsOverflowMenu(
                    listOf(
                        TestsMenuItem(
                            "Import suite…",
                            onClick = { importSuiteFromChosenFile(ui) },
                            enabled = !library.readOnly,
                            disabledHint = LIBRARY_READ_ONLY_MESSAGE,
                        ),
                        TestsMenuItem("Agent profiles…", onClick = { ui.state.openAiProviderSettings() }),
                    ),
                    contentDescription = "More suite actions",
                )
            }
            if (tree.isEmpty()) {
                TestsHint(
                    if (library.suites.isEmpty()) "No suites yet." else "No suite or case matches the search.",
                    Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                )
            }
            // A suite and its listed cases form one block, so the rows vary in height and are measured (no fixedRowHeight).
            // Dragging a suite's grip moves the whole block; the order shown while dragging comes from the measured heights.
            ReorderableColumn(
                items = tree,
                idOf = { it.suite.id },
                onMove = { id, to -> ui.report(ui.state.moveTestSuite(id, to)) },
                // A filtered list is not the stored order, so moving inside it would be ambiguous.
                reorderEnabled = !searching && !library.readOnly,
            ) { entry, row ->
                val suite = entry.suite
                val expanded = entry.forceExpanded || suite.id in view.expandedSuiteIds
                Column(Modifier.fillMaxWidth()) {
                    SuiteNavRow(
                        suite = suite,
                        row = row,
                        selected = view.nav == TestsNav.Suites && suite.id == selectedSuite?.id && activeCaseId == null,
                        expanded = expanded,
                        locked = limits.isSuiteLocked(suite.id),
                        onToggle = {
                            view.toggleSuiteExpanded(suite.id)
                            ui.reclaimFocus()
                        },
                    ) {
                        view.recordingReviewOpen = false
                        view.selectedSuiteId = suite.id
                        view.selectedCaseId = null
                        view.nav = TestsNav.Suites
                        view.expandSuite(suite.id)
                        ui.reclaimFocus()
                        onNavigate()
                    }
                    if (expanded) {
                        // Cases reorder inside their own suite; a filtered list is not the stored order, so not while searching.
                        ReorderableColumn(
                            items = entry.cases,
                            idOf = { it.id },
                            onMove = { id, to -> ui.report(ui.state.moveTestCase(id, to)) },
                            reorderEnabled = !searching && !library.readOnly && !suite.readOnly,
                        ) { case, caseRow ->
                            CaseNavRow(
                                name = case.name.ifBlank { "Untitled case" },
                                row = caseRow,
                                selected = view.nav == TestsNav.Suites && suite.id == selectedSuite?.id && case.id == activeCaseId,
                                locked = limits.isCaseLocked(case.id),
                            ) {
                                view.recordingReviewOpen = false
                                view.selectedSuiteId = suite.id
                                view.selectedCaseId = case.id
                                view.nav = TestsNav.Suites
                                ui.reclaimFocus()
                                onNavigate()
                            }
                        }
                    }
                }
            }
            NavSectionHeader("Library")

            fun go(nav: TestsNav, also: () -> Unit = {}) {
                view.recordingReviewOpen = false
                view.nav = nav
                also()
                ui.reclaimFocus()
                onNavigate()
            }
            NavItem("Shared steps", library.sharedSteps.size.toString(), view.nav == TestsNav.SharedSteps) { go(TestsNav.SharedSteps) }
            NavItem("Scripts", library.scripts.size.toString(), view.nav == TestsNav.Scripts) { go(TestsNav.Scripts) }
            NavItem("Examples for agents", null, view.nav == TestsNav.AgentExamples) { go(TestsNav.AgentExamples) }
            NavSectionHeader("Activity")
            NavItem("Runs", runCount.takeIf { it > 0 }?.toString(), view.nav == TestsNav.Runs) {
                go(TestsNav.Runs) { view.selectedRunId = null }
            }
            NavItem("Issues", issueCount?.takeIf { it > 0 }?.toString(), view.nav == TestsNav.Issues) {
                go(TestsNav.Issues) { view.selectedIssueId = null }
            }
        }
        Divider()
        val usage = remember(library, edition) { testsEditionUsage(library, edition.limits) }
        if (usage != null) {
            TestsUsageMeter(editionUsageLine(edition.label, usage), usage, Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp))
        } else {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                TestsBadge(edition.label)
                AppText(navCaseCountLabel(library.suites.sumOf { it.cases.size }), color = tc.td, fontSize = NAV_SMALL_FONT_SP.sp)
            }
        }
    }
}

/** Fixed actions in the Test Suites tile chrome, so suite creation/import remain available while the tree scrolls. */
@Composable
internal fun TestsSuitesTileHeaderAction() {
    val ui = LocalTestsUi.current
    val limits = LocalTestsLimits.current
    TestsIconButton(
        "+",
        "New suite",
        onClick = {
            ui.view.recordingReviewOpen = false
            createSuiteAndSelect(ui)
            ui.reclaimFocus()
        },
        enabled = limits.canCreateSuite && !ui.library.readOnly,
        disabledHint = if (ui.library.readOnly) LIBRARY_READ_ONLY_MESSAGE else limits.hint,
    )
    TestsOverflowMenu(
        listOf(
            TestsMenuItem(
                "Import suite…",
                onClick = { importSuiteFromChosenFile(ui) },
                enabled = !ui.library.readOnly,
                disabledHint = LIBRARY_READ_ONLY_MESSAGE,
            ),
            TestsMenuItem("Agent profiles…", onClick = { ui.state.openAiProviderSettings() }),
        ),
        contentDescription = "More suite actions",
    )
}

/** Searchable suite/case tree for the independent Test Suites tile. */
@Composable
internal fun TestsSuitesTileContent() {
    val tc = tc()
    val ui = LocalTestsUi.current
    val limits = LocalTestsLimits.current
    val view = ui.view
    val library = ui.library
    val edition by ui.state.editionService.current.collectAsState()
    val searching = view.search.isNotBlank()
    val tree = remember(library.suites, view.search) { filterNavTree(library.suites, view.search) }
    val selectedSuite = library.selectedSuiteOrFirst(view.selectedSuiteId)
    val activeCaseId = selectedSuite?.cases?.firstOrNull { it.id == view.selectedCaseId }?.id
    LaunchedEffect(selectedSuite?.id) { selectedSuite?.id?.let(view::expandSuite) }
    Column(Modifier.fillMaxSize().background(tc.p)) {
        TestsScrollColumn(Modifier.weight(1f), contentModifier = Modifier.padding(horizontal = 8.dp, vertical = 10.dp)) {
            InlineField(
                value = view.search,
                onValue = { view.search = it },
                placeholder = "Search suites and cases…",
                modifier = Modifier.fillMaxWidth(),
                fontSize = NAV_ITEM_FONT_SP.sp,
                onClear = { view.search = "" },
            )
            if (tree.isEmpty()) {
                TestsHint(
                    if (library.suites.isEmpty()) "No suites yet." else "No suite or case matches the search.",
                    Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                )
            }
            ReorderableColumn(
                items = tree,
                idOf = { it.suite.id },
                onMove = { id, to -> ui.report(ui.state.moveTestSuite(id, to)) },
                reorderEnabled = !searching && !library.readOnly,
            ) { entry, row ->
                val suite = entry.suite
                val expanded = entry.forceExpanded || suite.id in view.expandedSuiteIds
                Column(Modifier.fillMaxWidth()) {
                    SuiteNavRow(
                        suite = suite,
                        row = row,
                        selected = view.nav == TestsNav.Suites && suite.id == selectedSuite?.id && activeCaseId == null,
                        expanded = expanded,
                        locked = limits.isSuiteLocked(suite.id),
                        onToggle = {
                            view.toggleSuiteExpanded(suite.id)
                            ui.reclaimFocus()
                        },
                    ) {
                        view.recordingReviewOpen = false
                        view.selectedSuiteId = suite.id
                        view.selectedCaseId = null
                        view.nav = TestsNav.Suites
                        view.expandSuite(suite.id)
                        ui.reclaimFocus()
                    }
                    if (expanded) {
                        ReorderableColumn(
                            items = entry.cases,
                            idOf = { it.id },
                            onMove = { id, to -> ui.report(ui.state.moveTestCase(id, to)) },
                            reorderEnabled = !searching && !library.readOnly && !suite.readOnly,
                        ) { case, caseRow ->
                            CaseNavRow(
                                name = case.name.ifBlank { "Untitled case" },
                                row = caseRow,
                                selected = view.nav == TestsNav.Suites && suite.id == selectedSuite?.id && case.id == activeCaseId,
                                locked = limits.isCaseLocked(case.id),
                            ) {
                                view.recordingReviewOpen = false
                                view.selectedSuiteId = suite.id
                                view.selectedCaseId = case.id
                                view.nav = TestsNav.Suites
                                ui.reclaimFocus()
                            }
                        }
                    }
                }
            }
        }
        Divider()
        val usage = remember(library, edition) { testsEditionUsage(library, edition.limits) }
        if (usage != null) {
            TestsUsageMeter(editionUsageLine(edition.label, usage), usage, Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp))
        } else {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                TestsBadge(edition.label)
                AppText(navCaseCountLabel(library.suites.sumOf { it.cases.size }), color = tc.td, fontSize = NAV_SMALL_FONT_SP.sp)
            }
        }
    }
}

@Composable
internal fun TestsLibraryTileContent() {
    val ui = LocalTestsUi.current
    val library = ui.library
    TestsScrollColumn(Modifier.fillMaxSize(), contentModifier = Modifier.padding(horizontal = 8.dp, vertical = 10.dp)) {
        fun go(nav: TestsNav) {
            ui.view.recordingReviewOpen = false
            ui.view.nav = nav
            ui.reclaimFocus()
        }
        NavItem("Shared steps", library.sharedSteps.size.toString(), ui.view.nav == TestsNav.SharedSteps) { go(TestsNav.SharedSteps) }
        NavItem("Scripts", library.scripts.size.toString(), ui.view.nav == TestsNav.Scripts) { go(TestsNav.Scripts) }
        NavItem("Examples for agents", null, ui.view.nav == TestsNav.AgentExamples) { go(TestsNav.AgentExamples) }
    }
}

@Composable
internal fun TestsActivityTileContent() {
    val ui = LocalTestsUi.current
    val view = ui.view
    val issueRevision by ui.state.issueStore.revision.collectAsState()
    val issueCount by produceState<Int?>(null, issueRevision) { value = withContext(Dispatchers.IO) { ui.state.issueStore.list().size } }
    val runCount = ui.state.testRuns.size
    TestsScrollColumn(Modifier.fillMaxSize(), contentModifier = Modifier.padding(horizontal = 8.dp, vertical = 10.dp)) {
        NavItem("Runs", runCount.takeIf { it > 0 }?.toString(), view.nav == TestsNav.Runs) {
            view.recordingReviewOpen = false
            view.nav = TestsNav.Runs
            view.selectedRunId = null
            ui.reclaimFocus()
        }
        NavItem("Issues", issueCount?.takeIf { it > 0 }?.toString(), view.nav == TestsNav.Issues) {
            view.recordingReviewOpen = false
            view.nav = TestsNav.Issues
            view.selectedIssueId = null
            ui.reclaimFocus()
        }
    }
}

/**
 * The usage card at the bottom of the navigation on a limited edition: [line] ("Free · 1 of 1 suite · 5 of 5 cases"), a thin bar of the
 * unlocked cases against the case allowance (warn colour once it is full) and, when cases are locked, how many are read-only.
 */
@Composable
internal fun TestsUsageMeter(line: String, usage: TestsEditionUsage, modifier: Modifier = Modifier) {
    val tc = tc()
    val fraction = usage.caseCapacity?.takeIf { it > 0 }?.let { (usage.activeCases.toFloat() / it).coerceIn(0f, 1f) }
    val full = usage.caseCapacity != null && usage.activeCases >= usage.caseCapacity
    Column(
        modifier.background(tc.bg, CORNER_MD).border(1.dp, tc.br, CORNER_MD).padding(horizontal = 10.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(USAGE_METER_GAP),
    ) {
        AppText(line, color = tc.ts, fontSize = NAV_SMALL_FONT_SP.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
        if (fraction != null) {
            Box(Modifier.fillMaxWidth().height(USAGE_BAR_HEIGHT).background(tc.br.copy(alpha = USAGE_TRACK_ALPHA), CircleShape)) {
                Box(Modifier.fillMaxWidth(fraction).fillMaxHeight().background(if (full) tc.warn else tc.ac, CircleShape))
            }
        }
        if (usage.lockedCases > 0) {
            AppText("${countNoun(usage.lockedCases, "case")} read-only", color = tc.td, fontSize = NAV_SMALL_FONT_SP.sp)
        }
    }
}

/** A group heading of the tree ("Suites 3", "Library"); [trailing] holds the heading's buttons. */
@Composable
private fun NavSectionHeader(title: String, count: Int? = null, big: Boolean = false, trailing: (@Composable RowScope.() -> Unit)? = null) {
    val tc = tc()
    Row(
        Modifier.fillMaxWidth().heightIn(min = NAV_SECTION_MIN_HEIGHT).padding(horizontal = if (big) 0.dp else 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        DisableSelection {
            AppText(
                title,
                color = if (big) tc.ts else tc.td,
                fontSize = (if (big) NAV_ITEM_FONT_SP - 1 else NAV_SMALL_FONT_SP).sp,
                fontWeight = FontWeight.SemiBold,
            )
            count?.let { AppText("$it", color = tc.td, fontSize = NAV_SMALL_FONT_SP.sp) }
        }
        Spacer(Modifier.weight(1f))
        trailing?.invoke(this)
    }
}

/** The shared look of a tree row: hover feedback, the accent background while [selected], 28dp tall, clipped to the nav shape. */
@Composable
private fun NavRow(
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    startPadding: Dp = 8.dp,
    content: @Composable RowScope.() -> Unit,
) {
    TestsListRow(
        selected = selected,
        onClick = onClick,
        modifier = modifier.clip(NAV_ITEM_SHAPE),
        minHeight = NAV_ITEM_MIN_HEIGHT,
        startPadding = startPadding,
        spacing = 6.dp,
    ) { DisableSelection { content() } }
}

@Composable
private fun SuiteNavRow(
    suite: TestSuite,
    row: ReorderRowScope,
    selected: Boolean,
    expanded: Boolean,
    locked: Boolean,
    onToggle: () -> Unit,
    onClick: () -> Unit,
) {
    val tc = tc()
    val limits = LocalTestsLimits.current
    NavRow(selected = selected, onClick = onClick, startPadding = 4.dp) {
        if (suite.cases.isEmpty()) {
            Spacer(Modifier.size(NAV_CARET_SIZE))
        } else {
            HoverBox(
                Modifier.size(NAV_CARET_SIZE).clip(CORNER_SM).semantics { contentDescription = (if (expanded) "Collapse " else "Expand ") + suite.name },
                hoverBg = tc.br.copy(alpha = .7f),
                onClick = onToggle,
            ) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    AppText(if (expanded) "▾" else "▸", color = tc.td, fontSize = NAV_SMALL_FONT_SP.sp)
                }
            }
        }
        AppText(
            suite.name.ifBlank { "Untitled suite" },
            color = if (selected) tc.ac else tc.tx,
            fontSize = NAV_ITEM_FONT_SP.sp,
            fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (locked) LockBadge(limits.hint)
        TestsBadge("${suite.cases.size}")
        // Always visible (muted, dimmed while reordering is off: a search, a read-only library).
        ReorderGrip(row, Modifier.size(NAV_GRIP_SLOT))
    }
}

@Composable
private fun CaseNavRow(name: String, row: ReorderRowScope, selected: Boolean, locked: Boolean, onClick: () -> Unit) {
    val tc = tc()
    val limits = LocalTestsLimits.current
    NavRow(selected = selected, onClick = onClick, startPadding = NAV_CASE_START_PADDING) {
        AppText(
            name,
            color = if (selected) tc.ac else tc.ts,
            fontSize = NAV_ITEM_FONT_SP.sp,
            fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (locked) LockBadge(limits.hint)
        ReorderGrip(row, Modifier.size(NAV_GRIP_SLOT))
    }
}

@Composable
private fun NavItem(title: String, count: String?, selected: Boolean, onClick: () -> Unit) {
    val tc = tc()
    NavRow(selected = selected, onClick = onClick) {
        AppText(
            title,
            color = if (selected) tc.ac else tc.tx,
            fontSize = NAV_ITEM_FONT_SP.sp,
            fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        count?.let { TestsBadge(it) }
    }
}

// ── Actions shared by the nav pane and the suite screen ──────────────

/** Creates a suite named "New suite" (or "New suite 2", ...) in [AppState]'s library; shared by the Tests workspace and the New tab. */
internal fun AppState.createNewTestSuite(): StoreResult<TestSuite> =
    createTestSuite(uniqueName(NEW_SUITE_NAME, testLibrary.suites.map { it.name }))

internal fun createSuiteAndSelect(ui: TestsUi) {
    ui.view.recordingReviewOpen = false
    val result = ui.report(ui.state.createNewTestSuite())
    if (result is StoreResult.Ok) {
        ui.view.selectedSuiteId = result.value.id
        ui.view.selectedCaseId = null
        ui.view.nav = TestsNav.Suites
    }
}

/** Asks for a suite file and imports it off the UI thread; the outcome (and any warning) lands in the banner. */
internal fun importSuiteFromChosenFile(ui: TestsUi) {
    val file = pickOpenFile("Import test suite") ?: return
    ui.reclaimFocus()
    ui.scope.launch(Dispatchers.IO) {
        val result = ui.report(ui.state.importTestSuiteFromFile(file))
        if (result is StoreResult.Ok) {
            ui.view.recordingReviewOpen = false
            ui.view.selectedSuiteId = result.value.id
            ui.view.selectedCaseId = null
            ui.view.nav = TestsNav.Suites
            if (result.warnings.isEmpty()) ui.info("Imported suite \"${result.value.name}\".")
        }
    }
}

/** The native "open file" prompt (no extension filter: it is unreliable on macOS; the content is validated after). */
internal fun pickOpenFile(title: String): File? {
    val dialog = FileDialog(null as Frame?, title, FileDialog.LOAD)
    dialog.isVisible = true
    val name = dialog.file ?: return null
    val dir = dialog.directory ?: return null
    return File(dir, name)
}

/** A file name for an exported suite: the suite's name reduced to safe characters, plus `.json`. */
internal fun suiteExportFileName(suiteName: String): String {
    val safe = suiteName.trim().replace(Regex("[^A-Za-z0-9._-]+"), "_").trim('_', '.').take(EXPORT_NAME_MAX_CHARS).ifBlank { "test_suite" }
    return "$safe.json"
}

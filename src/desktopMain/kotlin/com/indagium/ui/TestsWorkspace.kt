@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class, androidx.compose.foundation.ExperimentalFoundationApi::class)

package com.indagium.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.ui.input.key.onPreviewKeyEvent
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

// The AI test-suites workspace: a single full-width, non-log surface (ActiveSurface.Tests) shown in its own tab.
// Left: a navigation tree (searchable suites with their cases, then the library and activity screens, and a footer with
// the edition). Centre: the screen of the selected item, optionally with an inspector beside it (TestsSplitScreen).
// Below ~600dp the tree collapses to a ☰ toggle. Selection lives in AppState.testsView so it survives switching tabs.
// Edits go straight through the AppState delegates (which return StoreResult); failures show inline or in the banner.

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
    CompositionLocalProvider(LocalTestsUi provides ui, LocalTestsLimits provides limits, LocalTextInputFocusTracker provides ui.textFocus) {
        Column(
            Modifier.fillMaxSize().background(tc.bg)
                // Bare keys (arrows, Esc) go to the current screen, but never while a text input has focus (see TestsUi.routeKey).
                .onPreviewKeyEvent { ui.routeKey(it) }
                .focusRequester(rootFocus).focusable(),
        ) {
            state.testStepRecordingSession?.let { session ->
                val recording by session.snapshot.collectAsState()
                Row(
                    Modifier.fillMaxWidth().background(tc.warnBg).padding(horizontal = 12.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    AppText(
                        if (recording.active) "Recording accepted input from ${session.deviceSerial} · ${recording.steps.size} step(s)"
                        else "Recorded draft ready · ${recording.steps.size} step(s)",
                        color = tc.tx, fontSize = 10.sp, modifier = Modifier.weight(1f),
                    )
                    if (recording.active) AppButton("Stop recording", onClick = { state.stopTestStepRecording() }, variant = ButtonVariant.Secondary)
                    state.testStepRecordingTarget?.let { (suiteId, caseId) ->
                        AppButton("Review", onClick = {
                            view.selectedSuiteId = suiteId
                            view.selectedCaseId = caseId
                            view.nav = TestsNav.Suites
                        }, variant = ButtonVariant.Primary)
                    }
                    AppButton(
                        "Discard",
                        onClick = { state.clearTestStepRecording(session.id) },
                        enabled = !state.isTestStepRecordingApplying(session.id),
                        variant = ButtonVariant.Ghost,
                    )
                }
            }
            ui.banner?.let { TestsBannerView(it) { ui.banner = null } }
            limits.banner?.let { TestsLockedNotice(it, Modifier.padding(horizontal = 12.dp, vertical = 6.dp)) }
            if (library.readOnly) TestsLockedNotice(LIBRARY_READ_ONLY_MESSAGE, Modifier.padding(horizontal = 12.dp, vertical = 6.dp))
            state.testLibraryPersistError?.let { TestsBannerView(TestsBanner(it, isError = true), dismissible = false) { } }
            state.testStorageStatus?.let { TestsBannerView(TestsBanner(it, isError = false), dismissible = false) { } }
            view.runDialog?.let { target -> TestRunDialog(target) { view.runDialog = null } }
            view.improveDialog?.let { target -> TestStepImproveDialog(target) { view.improveDialog = null } }
            view.issueDialog?.let { target -> IssueDraftDialog(target) { view.issueDialog = null } }
            BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
                if (testsNavCollapsed(maxWidth.value)) {
                    TestsNarrowLayout(maxWidth)
                } else {
                    Row(Modifier.fillMaxSize()) {
                        TestsNavPane(Modifier.width(view.navWidthDp.dp).fillMaxHeight())
                        HDivider { delta -> view.navWidthDp = clampTestsNavWidth(view.navWidthDp, delta) }
                        TestsCenter(Modifier.weight(1f).fillMaxHeight())
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
            TestsCenter(Modifier.fillMaxSize())
            if (navOpen) {
                Row(Modifier.fillMaxSize()) {
                    TestsNavPane(
                        Modifier.width(minOf(totalWidth * NAV_OVERLAY_WIDTH_FRACTION, NAV_OVERLAY_MAX_WIDTH)).fillMaxHeight(),
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
 * The common frame of a centre screen: scrolls, padded, content capped to a readable width. With an [inspector] the
 * screen becomes a [TestsSplitScreen] (inspector beside the content, or under it in a narrow window).
 */
@Composable
internal fun TestsScreenScaffold(
    modifier: Modifier = Modifier,
    inspector: (@Composable () -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    TestsSplitScreen(
        main = { Column(Modifier.fillMaxWidth().widthIn(max = TESTS_CONTENT_MAX_WIDTH).padding(16.dp)) { content() } },
        inspector = inspector,
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

internal fun createSuiteAndSelect(ui: TestsUi) {
    val name = uniqueName(NEW_SUITE_NAME, ui.library.suites.map { it.name })
    val result = ui.report(ui.state.createTestSuite(name))
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

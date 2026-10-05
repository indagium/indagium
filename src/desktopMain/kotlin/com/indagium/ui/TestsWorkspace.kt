@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class, androidx.compose.foundation.ExperimentalFoundationApi::class)

package com.indagium.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.DisableSelection
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.indagium.testing.model.TestLibrary
import com.indagium.testing.model.TestSuite
import com.indagium.testing.store.StoreResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.awt.FileDialog
import java.awt.Frame
import java.io.File

// The AI test-suites workspace: a single full-width, non-log surface (ActiveSurface.Tests) shown in its own tab.
// Left: navigation (suite search + list, shared steps, scripts, placeholders for runs/issues). Centre: the screen of
// the selected item. Selection lives in AppState.testsView so it survives switching tabs. Edits go straight
// through the AppState delegates (which return StoreResult); failures show inline or in the banner.

private val NARROW_BREAKPOINT = 720.dp
private val NARROW_NAV_MAX_HEIGHT = 260.dp
private val SUITE_ROW_HEIGHT = 34.dp
private val NAV_ITEM_SHAPE = RoundedCornerShape(6.dp)
internal val TESTS_CONTENT_MAX_WIDTH = 940.dp
internal const val TESTS_LATER_VERSION_TEXT = "Available in a later version"
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
    CompositionLocalProvider(LocalTestsUi provides ui, LocalTestsLimits provides limits) {
        Column(Modifier.fillMaxSize().background(tc.bg).focusRequester(rootFocus).focusable()) {
            TestsHeader(edition.label, library)
            ui.banner?.let { TestsBannerView(it) { ui.banner = null } }
            limits.banner?.let { TestsLockedNotice(it, Modifier.padding(horizontal = 12.dp, vertical = 6.dp)) }
            if (library.readOnly) TestsLockedNotice(LIBRARY_READ_ONLY_MESSAGE, Modifier.padding(horizontal = 12.dp, vertical = 6.dp))
            state.testLibraryPersistError?.let { TestsBannerView(TestsBanner(it, isError = true)) { } }
            view.runDialog?.let { target -> TestRunDialog(target) { view.runDialog = null } }
            BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
                if (maxWidth < NARROW_BREAKPOINT) {
                    Column(Modifier.fillMaxSize()) {
                        TestsNavPane(Modifier.fillMaxWidth().heightIn(max = NARROW_NAV_MAX_HEIGHT))
                        Box(Modifier.fillMaxWidth().height(1.dp).background(tc.br))
                        TestsCenter(Modifier.weight(1f).fillMaxWidth())
                    }
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

@Composable
private fun TestsHeader(editionLabel: String, library: TestLibrary) {
    val tc = tc()
    Row(
        Modifier.fillMaxWidth().background(tc.p).padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        AppText("AI test suites", color = tc.tx, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
        TestsBadge(editionLabel)
        Spacer(Modifier.weight(1f))
        AppText(
            "${library.suites.size} suite(s) · ${library.suites.sumOf { it.cases.size }} case(s)",
            color = tc.td,
            fontSize = 11.sp,
        )
    }
    Box(Modifier.fillMaxWidth().height(1.dp).background(tc.br))
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
            TestsNav.Issues -> TestsPlaceholderScreen("Issues", "Failed steps will become issues you can send to your tracker here.")
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

@Composable
private fun TestsPlaceholderScreen(title: String, description: String) {
    val tc = tc()
    TestsScreenScaffold {
        AppText(title, color = tc.tx, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(6.dp))
        AppText(TESTS_LATER_VERSION_TEXT, color = tc.warn, fontSize = 12.sp)
        Spacer(Modifier.height(6.dp))
        TestsHint(description)
    }
}

/** The common frame of a centre screen: scrolls, padded, content capped to a readable width. */
@Composable
internal fun TestsScreenScaffold(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Box(modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        Column(Modifier.fillMaxWidth().widthIn(max = TESTS_CONTENT_MAX_WIDTH).padding(16.dp)) { content() }
    }
}

// ── Navigation pane ──────────────────────────────────────────────────

@Composable
private fun TestsNavPane(modifier: Modifier) {
    val tc = tc()
    val ui = LocalTestsUi.current
    val limits = LocalTestsLimits.current
    val view = ui.view
    val library = ui.library
    val shown = filterSuites(library.suites, view.search)
    val selectedSuite = library.selectedSuiteOrFirst(view.selectedSuiteId)
    Column(modifier.background(tc.p).verticalScroll(rememberScrollState()).padding(10.dp)) {
        InlineField(
            value = view.search,
            onValue = { view.search = it },
            placeholder = "Search suites…",
            modifier = Modifier.fillMaxWidth(),
            fontSize = 12.sp,
            onClear = { view.search = "" },
        )
        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            HintedButton(
                "New suite",
                onClick = { createSuiteAndSelect(ui) },
                enabled = limits.canCreateSuite && !library.readOnly,
                disabledHint = if (library.readOnly) LIBRARY_READ_ONLY_MESSAGE else limits.hint,
                variant = ButtonVariant.Primary,
                modifier = Modifier.weight(1f),
            )
            HintedButton(
                "Import…",
                onClick = { importSuiteFromChosenFile(ui) },
                enabled = !library.readOnly,
                disabledHint = LIBRARY_READ_ONLY_MESSAGE,
            )
        }
        TestsSectionTitle("Suites")
        if (shown.isEmpty()) {
            TestsHint(if (library.suites.isEmpty()) "No suites yet." else "No suite matches the search.")
        }
        ReorderableColumn(
            items = shown,
            idOf = { it.id },
            onMove = { id, to -> ui.report(ui.state.moveTestSuite(id, to)) },
            fixedRowHeight = SUITE_ROW_HEIGHT,
            // A filtered list is not the stored order, so moving inside it would be ambiguous.
            reorderEnabled = view.search.isBlank() && !library.readOnly,
        ) { suite, row ->
            SuiteNavRow(
                suite = suite,
                row = row,
                selected = view.nav == TestsNav.Suites && suite.id == selectedSuite?.id,
                locked = limits.isSuiteLocked(suite.id),
            ) {
                view.selectedSuiteId = suite.id
                view.selectedCaseId = null
                view.nav = TestsNav.Suites
            }
        }
        TestsSectionTitle("Library")
        NavItem("Shared steps", library.sharedSteps.size.toString(), view.nav == TestsNav.SharedSteps) { view.nav = TestsNav.SharedSteps }
        NavItem("Scripts", library.scripts.size.toString(), view.nav == TestsNav.Scripts) { view.nav = TestsNav.Scripts }
        NavItem("Runs", ui.state.testRuns.size.takeIf { it > 0 }?.toString(), view.nav == TestsNav.Runs) {
            view.nav = TestsNav.Runs
            view.selectedRunId = null
        }
        NavItem("Issues", null, view.nav == TestsNav.Issues, subtitle = TESTS_LATER_VERSION_TEXT) { view.nav = TestsNav.Issues }
    }
}

@Composable
private fun SuiteNavRow(suite: TestSuite, row: ReorderRowScope, selected: Boolean, locked: Boolean, onClick: () -> Unit) {
    val tc = tc()
    val limits = LocalTestsLimits.current
    Row(
        Modifier.fillMaxSize().clip(NAV_ITEM_SHAPE)
            .background(if (selected) tc.ac.copy(alpha = .16f) else Color.Transparent)
            .clickable(onClick = onClick).padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        ReorderGrip(row)
        AppText(
            suite.name.ifBlank { "Untitled suite" },
            color = if (selected) tc.ac else tc.tx,
            fontSize = 12.sp,
            fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (locked) LockBadge(limits.hint)
        TestsBadge("${suite.cases.size}")
    }
}

@Composable
private fun NavItem(title: String, count: String?, selected: Boolean, subtitle: String? = null, onClick: () -> Unit) {
    val tc = tc()
    Column(
        Modifier.fillMaxWidth().clip(NAV_ITEM_SHAPE)
            .background(if (selected) tc.ac.copy(alpha = .16f) else Color.Transparent)
            .clickable(onClick = onClick).padding(horizontal = 10.dp, vertical = 6.dp),
    ) {
        DisableSelection {
            Row(verticalAlignment = Alignment.CenterVertically) {
                AppText(
                    title,
                    color = if (selected) tc.ac else if (subtitle != null) tc.ts else tc.tx,
                    fontSize = 12.sp,
                    fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal,
                    modifier = Modifier.weight(1f),
                )
                count?.let { TestsBadge(it) }
            }
            subtitle?.let { AppText(it, color = tc.td, fontSize = 10.sp) }
        }
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

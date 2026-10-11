package com.indagium.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.indagium.testing.model.StepExample
import com.indagium.testing.model.TestLibrary
import com.indagium.testing.model.TestStep

// The Examples-for-agents screen: every example already attached to a step, grouped by the case (or shared step) that owns it, each
// row a kind badge, the caption and a two-line preview. The selected example is shown in full in the inspector, whose "Open step"
// leads to the step's own Examples section, where it is edited. The screen itself changes nothing.

private val EXAMPLE_ROW_MIN_HEIGHT = 52.dp
private val EXAMPLE_FULL_MAX_HEIGHT = 340.dp
private val EXAMPLE_IMAGE_SIZE = 220.dp
private const val PREVIEW_LINES = 2
private const val FULL_TEXT_MAX_LINES = 2000

@Composable
internal fun TestsAgentExamplesScreen() {
    val ui = LocalTestsUi.current
    val examples = agentExampleRows(ui.library)
    val grouped = examples.groupBy { it.ownerName }
    // A selection that is gone (its example was deleted) falls back to the first example, so the inspector is never an empty pane.
    val selected = examples.firstOrNull { it.key == ui.view.selectedExampleKey } ?: examples.firstOrNull()
    TestsScreenScaffold(inspector = selected?.let { item -> { key(item.key) { ExampleInspector(item) } } }) {
        TestsPageHeader("Examples for agents")
        Spacer(Modifier.height(6.dp))
        TestsHint("Examples are reference material already attached to steps. Select one to see it in full and open its step.")
        Spacer(Modifier.height(10.dp))
        if (examples.isEmpty()) {
            TestsHint("No step examples yet. Add a golden screenshot or reference log in a step editor.")
        }
        grouped.forEach { (owner, rows) ->
            SectionHeader(owner)
            rows.forEach { item -> ExampleRow(item, selected = item.key == selected?.key) { ui.view.selectedExampleKey = item.key } }
            Spacer(Modifier.height(8.dp))
        }
    }
}

/** One example: kind badge, caption (the step's action while it has none) and a preview clamped to two lines. */
@Composable
private fun ExampleRow(item: AgentExampleRow, selected: Boolean, onClick: () -> Unit) {
    val tc = tc()
    val ui = LocalTestsUi.current
    TestsListRow(
        selected = selected,
        onClick = {
            ui.reclaimFocus()
            onClick()
        },
        minHeight = EXAMPLE_ROW_MIN_HEIGHT,
    ) {
        Column(Modifier.weight(1f).padding(vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                TestsBadge(item.example.kindLabel())
                AppText(
                    item.example.caption.ifBlank { item.step.action.ifBlank { item.example.kindLabel() } },
                    color = tc.tx, fontSize = 12.sp, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
                )
            }
            when (val example = item.example) {
                is StepExample.ReferenceLog -> if (example.text.isNotBlank()) {
                    AppText(example.text, color = tc.td, fontSize = 10.sp, fontFamily = MONO, maxLines = PREVIEW_LINES, overflow = TextOverflow.Ellipsis)
                }
                is StepExample.GoldenScreenshot ->
                    AppText(example.assetPath, color = tc.td, fontSize = 10.sp, fontFamily = MONO, maxLines = PREVIEW_LINES, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

/** The selected example in full: where it is, its caption, the whole log (scrolling) or the image, and Open step. */
@Composable
private fun ExampleInspector(item: AgentExampleRow) {
    val tc = tc()
    val ui = LocalTestsUi.current
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        TestsInspectorHeader("${item.example.kindLabel()} · ${item.ownerShortName}", item.step.action.ifBlank { "Untitled step" })
        if (item.example.caption.isNotBlank()) {
            TestsLabeled("Caption") { AppText(item.example.caption, color = tc.tx, fontSize = 12.sp, maxLines = 3, overflow = TextOverflow.Ellipsis) }
        }
        when (val example = item.example) {
            is StepExample.ReferenceLog -> TestsLabeled("Content") {
                if (example.text.isBlank()) {
                    TestsHint("This log is empty.")
                } else {
                    TestsScrollColumn(
                        Modifier.fillMaxWidth().heightIn(max = EXAMPLE_FULL_MAX_HEIGHT).background(tc.p2, CORNER_MD).border(1.dp, tc.br, CORNER_MD),
                        contentModifier = Modifier.padding(10.dp),
                    ) {
                        AppText(example.text, color = tc.tx, fontSize = 11.sp, fontFamily = MONO, maxLines = FULL_TEXT_MAX_LINES)
                    }
                }
            }
            is StepExample.GoldenScreenshot -> {
                val file = item.suiteId?.let { ui.state.testGoldenImageFile(it, example.assetPath) }
                TestsLabeled("Screenshot") {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        GoldenThumbnail(file, EXAMPLE_IMAGE_SIZE)
                        AppText(example.assetPath, color = tc.ts, fontSize = 10.sp, fontFamily = MONO, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        if (file == null || !file.isFile) TestsErrorText("The image file is missing from this suite's assets.")
                    }
                }
            }
        }
        Row {
            AppButton("Open step", onClick = { openExampleStep(ui, item) }, variant = ButtonVariant.Primary)
        }
    }
}

/** Opens the step that owns [item], selected, with its Examples section expanded (what a click on the row did before the inspector). */
private fun openExampleStep(ui: TestsUi, item: AgentExampleRow) {
    val view = ui.view
    if (item.sharedStepId != null) {
        view.nav = TestsNav.SharedSteps
        view.selectedSharedStepId = item.sharedStepId
    } else {
        view.nav = TestsNav.Suites
        view.selectedSuiteId = item.suiteId
        view.selectedCaseId = item.caseId
    }
    view.selectStep(item.step.id)
    view.revealStepId = item.step.id
    view.stepExamplesExpanded = true
    ui.reclaimFocus()
}

/** Every example of every case step and shared step, in library order. */
private fun agentExampleRows(library: TestLibrary): List<AgentExampleRow> {
    val caseExamples = library.suites.flatMap { suite ->
        suite.cases.flatMap { case ->
            case.steps.flatMap { step ->
                step.examples.map { example ->
                    AgentExampleRow("${suite.name} › ${case.name}", case.name, step, example, suiteId = suite.id, caseId = case.id)
                }
            }
        }
    }
    val sharedExamples = library.sharedSteps.flatMap { shared ->
        shared.steps.flatMap { step ->
            step.examples.map { example -> AgentExampleRow("Shared steps › ${shared.name}", shared.name, step, example, sharedStepId = shared.id) }
        }
    }
    return caseExamples + sharedExamples
}

private data class AgentExampleRow(
    /** The group header: "<suite> › <case>" or "Shared steps › <name>". */
    val ownerName: String,
    val ownerShortName: String,
    val step: TestStep,
    val example: StepExample,
    val suiteId: String? = null,
    val caseId: String? = null,
    val sharedStepId: String? = null,
) {
    val key: String get() = "${step.id}/${example.id}"
}

private fun StepExample.kindLabel(): String = when (this) {
    is StepExample.GoldenScreenshot -> "Golden screenshot"
    is StepExample.ReferenceLog -> "Reference log"
}

@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.indagium.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.indagium.testing.model.DEFAULT_LOG_ABSENT_FOR_MS
import com.indagium.testing.model.DEFAULT_LOG_WITHIN_MS
import com.indagium.testing.model.OnFailure
import com.indagium.testing.model.StepCheck
import com.indagium.testing.model.StepExample
import com.indagium.testing.model.TestStep
import com.indagium.testing.model.moveById
import com.indagium.testing.model.newCheckId
import com.indagium.testing.model.newExampleId
import com.indagium.testing.model.newStepId
import com.indagium.testing.store.MAX_STEP_MAX_TOOL_CALLS
import com.indagium.testing.store.MAX_STEP_RETRIES
import com.indagium.testing.store.MAX_STEP_TIMEOUT_MS
import com.indagium.testing.store.MIN_STEP_MAX_TOOL_CALLS
import com.indagium.testing.store.StoreResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

// The step list of a case or a shared step, and the step editor. Both owners edit steps the same way, so the screens
// hand in a StepListOps that knows how to persist a change; nothing here talks to a store directly.

private const val NEW_STEP_ACTION = "New step"
private const val DEFAULT_CHECK_REGEX = ".+"
private val STEP_FIELD_WIDTH = 150.dp
private val NUMBER_FIELD_WIDTH = 110.dp
private val THUMB_HEIGHT = 72.dp
private val STEP_MENU_WIDTH = 200.dp

/** How a list of steps is read and changed; [update] receives the STORED step so back-to-back edits never overwrite each other. */
internal class StepListOps(
    val steps: List<TestStep>,
    val editable: Boolean,
    /** The suite whose asset folder holds golden screenshots; null when the owner has no suite (shared steps). */
    val assetSuiteId: String?,
    val update: (stepId: String, transform: (TestStep) -> TestStep) -> StoreResult<*>,
    val add: (TestStep) -> StoreResult<*>,
    val delete: (String) -> StoreResult<*>,
    val duplicate: (String) -> StoreResult<*>,
    val move: (String, Int) -> StoreResult<*>,
)

// ── Step list ────────────────────────────────────────────────────────

@Composable
internal fun StepListSection(ops: StepListOps) {
    val ui = LocalTestsUi.current
    TestsSectionTitle("Steps (${ops.steps.size})") {
        if (ops.editable) {
            AppButton("+ Add step", onClick = {
                val step = TestStep(newStepId(), NEW_STEP_ACTION)
                if (ui.report(ops.add(step)) is StoreResult.Ok) ui.view.expandedStepId = step.id
            }, variant = ButtonVariant.Ghost)
        }
    }
    if (ops.steps.isEmpty()) TestsHint("No steps yet. A step is one thing the agent does, with what you expect to see afterwards.")
    ReorderableColumn(
        items = ops.steps,
        idOf = { it.id },
        onMove = { id, to -> ui.report(ops.move(id, to)) },
        reorderEnabled = ops.editable,
        rowGap = 6.dp,
    ) { step, row -> StepCard(step, row, ops) }
}

@Composable
private fun StepCard(step: TestStep, row: ReorderRowScope, ops: StepListOps) {
    val tc = tc()
    val ui = LocalTestsUi.current
    val expanded = ui.view.expandedStepId == step.id
    Column(Modifier.fillMaxWidth().background(tc.p2, CORNER_MD).border(1.dp, if (expanded) tc.ac.copy(alpha = .5f) else tc.br, CORNER_MD)) {
        Row(Modifier.fillMaxWidth().padding(6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            ReorderGrip(row)
            Row(
                Modifier.weight(1f).clickable { ui.view.expandedStepId = if (expanded) null else step.id },
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                AppText("${row.index + 1}", color = tc.td, fontSize = 10.sp, fontFamily = MONO, modifier = Modifier.width(18.dp))
                AppText(
                    step.action.ifBlank { "(no action)" },
                    color = tc.tx,
                    fontSize = 12.sp,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                stepCheckBadges(step).forEach { TestsBadge(it) }
                AppText(if (expanded) "▾" else "▸", color = tc.td, fontSize = 10.sp)
            }
            ReorderMoveButtons(row)
            SquareIconButton("⧉", fontSize = 12.sp, enabled = ops.editable, onClick = { ui.report(ops.duplicate(step.id)) })
            SquareIconButton("×", fontSize = 14.sp, enabled = ops.editable, onClick = {
                ui.report(ops.delete(step.id))
                ui.reclaimFocus()
            })
        }
        if (expanded) {
            Box(Modifier.fillMaxWidth().height(1.dp).background(tc.br))
            StepEditor(step, ops)
        }
    }
}

// ── Step editor ──────────────────────────────────────────────────────

@Composable
private fun StepEditor(step: TestStep, ops: StepListOps) {
    val update: ((TestStep) -> TestStep) -> StoreResult<*> = { transform -> ops.update(step.id, transform) }
    Column(Modifier.fillMaxWidth().padding(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        TestsLabeled("Action") {
            CommitTextField(
                step.action, { text -> update { it.copy(action = text) } },
                enabled = ops.editable, multiline = true, placeholder = "What the agent should do",
            )
        }
        TestsLabeled("Expected result") {
            CommitTextField(
                step.expected, { text -> update { it.copy(expected = text) } },
                enabled = ops.editable, multiline = true, placeholder = "What should be true afterwards",
            )
        }
        ChecksEditor(step, ops)
        ExamplesEditor(step, ops)
        StepSettings(step, ops)
    }
}

private val ON_FAILURE_LABELS = mapOf(
    OnFailure.STOP_CASE to "Stop the case",
    OnFailure.CONTINUE to "Continue",
    OnFailure.CREATE_ISSUE_AND_CONTINUE to "Create issue, continue",
    OnFailure.PAUSE_FOR_USER to "Pause for me",
)

@Composable
private fun StepSettings(step: TestStep, ops: StepListOps) {
    val update: ((TestStep) -> TestStep) -> StoreResult<*> = { transform -> ops.update(step.id, transform) }
    TestsSectionTitle("Settings")
    FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        TestsLabeled("When it fails", Modifier.width(STEP_FIELD_WIDTH + 50.dp)) {
            TestsDropdown(
                selectedLabel = ON_FAILURE_LABELS.getValue(step.onFailure),
                options = OnFailure.entries,
                optionLabel = { ON_FAILURE_LABELS.getValue(it) },
                onSelect = { choice -> update { it.copy(onFailure = choice) } },
                enabled = ops.editable,
                menuWidth = STEP_MENU_WIDTH,
                isSelected = { it == step.onFailure },
            )
        }
        TestsLabeled("Timeout (ms)", Modifier.width(NUMBER_FIELD_WIDTH)) {
            CommitNumberField(step.timeoutMs, 1L, MAX_STEP_TIMEOUT_MS, { n -> update { it.copy(timeoutMs = n) } }, enabled = ops.editable)
        }
        TestsLabeled("Retries", Modifier.width(NUMBER_FIELD_WIDTH)) {
            CommitNumberField(step.retries.toLong(), 0L, MAX_STEP_RETRIES.toLong(), { n -> update { it.copy(retries = n.toInt()) } }, enabled = ops.editable)
        }
        TestsLabeled("Max tool calls", Modifier.width(NUMBER_FIELD_WIDTH)) {
            CommitNumberField(
                step.maxToolCalls.toLong(), MIN_STEP_MAX_TOOL_CALLS.toLong(), MAX_STEP_MAX_TOOL_CALLS.toLong(),
                { n -> update { it.copy(maxToolCalls = n.toInt()) } }, enabled = ops.editable,
            )
        }
    }
}

// ── Checks ───────────────────────────────────────────────────────────

private enum class CheckKind(val label: String) {
    LOG_APPEARS("Log appears"),
    LOG_ABSENT("Log absent"),
    SCREEN_JUDGE("Screen judge"),
    SCRIPT_RESULT("Script result"),
    ASK_JUDGE("Ask the judge"),
}

private inline fun <reified C : StepCheck> TestStep.mapCheck(id: String, transform: (C) -> C): TestStep =
    copy(checks = checks.map { if (it.id == id && it is C) transform(it) else it })

@Composable
private fun ChecksEditor(step: TestStep, ops: StepListOps) {
    val ui = LocalTestsUi.current
    val library = ui.library
    val kinds = CheckKind.entries.filter { it != CheckKind.SCRIPT_RESULT || library.scripts.isNotEmpty() }
    val update: ((TestStep) -> TestStep) -> StoreResult<*> = { transform -> ops.update(step.id, transform) }
    TestsSectionTitle("Checks (${step.checks.size})") {
        if (ops.editable) {
            TestsDropdown(
                selectedLabel = "+ Add check",
                options = kinds,
                optionLabel = { it.label },
                onSelect = { kind ->
                    val check = newCheck(kind, library.scripts.firstOrNull()?.id.orEmpty())
                    ui.report(update { it.copy(checks = it.checks + check) })
                },
            )
        }
    }
    if (step.checks.isEmpty()) TestsHint("None. Without checks the judge decides from the expected result alone.")
    ReorderableColumn(
        items = step.checks,
        idOf = { it.id },
        onMove = { id, to -> ui.report(update { s -> s.copy(checks = s.checks.moveById(id, to) { it.id }) }) },
        reorderEnabled = ops.editable,
        rowGap = 4.dp,
    ) { check, row ->
        ReorderRowCard(row, ops.editable, onRemove = {
            ui.report(update { s -> s.copy(checks = s.checks.filterNot { it.id == check.id }) })
            ui.reclaimFocus()
        }) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) { CheckBody(check, step, ops.editable, update) }
        }
    }
}

private fun newCheck(kind: CheckKind, firstScriptId: String): StepCheck = when (kind) {
    CheckKind.LOG_APPEARS -> StepCheck.LogAppears(newCheckId(), regex = DEFAULT_CHECK_REGEX, withinMs = DEFAULT_LOG_WITHIN_MS)
    CheckKind.LOG_ABSENT -> StepCheck.LogAbsent(newCheckId(), regex = DEFAULT_CHECK_REGEX, forMs = DEFAULT_LOG_ABSENT_FOR_MS)
    CheckKind.SCREEN_JUDGE -> StepCheck.ScreenJudge(newCheckId(), "")
    CheckKind.SCRIPT_RESULT -> StepCheck.ScriptResult(newCheckId(), firstScriptId)
    CheckKind.ASK_JUDGE -> StepCheck.AskJudge(newCheckId(), "")
}

@Composable
private fun CheckBody(check: StepCheck, step: TestStep, editable: Boolean, update: ((TestStep) -> TestStep) -> StoreResult<*>) {
    when (check) {
        is StepCheck.LogAppears -> LogCheckBody(
            badge = checkKindLabel(check), tag = check.tag, regex = check.regex, durationLabel = "within ms", durationMs = check.withinMs, editable = editable,
            onTag = { t -> update { s -> s.mapCheck<StepCheck.LogAppears>(check.id) { it.copy(tag = t) } } },
            onRegex = { r -> update { s -> s.mapCheck<StepCheck.LogAppears>(check.id) { it.copy(regex = r) } } },
            onDuration = { n -> update { s -> s.mapCheck<StepCheck.LogAppears>(check.id) { it.copy(withinMs = n) } } },
        )
        is StepCheck.LogAbsent -> LogCheckBody(
            badge = checkKindLabel(check), tag = check.tag, regex = check.regex, durationLabel = "for ms", durationMs = check.forMs, editable = editable,
            onTag = { t -> update { s -> s.mapCheck<StepCheck.LogAbsent>(check.id) { it.copy(tag = t) } } },
            onRegex = { r -> update { s -> s.mapCheck<StepCheck.LogAbsent>(check.id) { it.copy(regex = r) } } },
            onDuration = { n -> update { s -> s.mapCheck<StepCheck.LogAbsent>(check.id) { it.copy(forMs = n) } } },
        )
        is StepCheck.ScreenJudge -> ScreenJudgeBody(check, step, editable, update)
        is StepCheck.ScriptResult -> ScriptResultBody(check, editable, update)
        is StepCheck.AskJudge -> {
            TestsBadge(checkKindLabel(check))
            CommitTextField(
                check.text, { t -> update { s -> s.mapCheck<StepCheck.AskJudge>(check.id) { it.copy(text = t) } } },
                enabled = editable, multiline = true, placeholder = "A question the judge answers yes or no",
            )
        }
    }
}

@Composable
private fun LogCheckBody(
    badge: String,
    tag: String?,
    regex: String,
    durationLabel: String,
    durationMs: Long,
    editable: Boolean,
    onTag: (String?) -> StoreResult<*>,
    onRegex: (String) -> StoreResult<*>,
    onDuration: (Long) -> StoreResult<*>,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        TestsBadge(badge)
        CommitTextField(tag.orEmpty(), { onTag(it.trim().ifEmpty { null }) }, Modifier.weight(1f), editable, "tag (optional)", mono = true)
        AppText(durationLabel, color = tc().td, fontSize = 10.sp)
        CommitNumberField(durationMs, 1L, MAX_STEP_TIMEOUT_MS, onDuration, Modifier.width(NUMBER_FIELD_WIDTH), editable)
    }
    CommitTextField(regex, onRegex, enabled = editable, placeholder = "regular expression", mono = true)
}

@Composable
private fun ScreenJudgeBody(check: StepCheck.ScreenJudge, step: TestStep, editable: Boolean, update: ((TestStep) -> TestStep) -> StoreResult<*>) {
    TestsBadge(checkKindLabel(check))
    CommitTextField(
        check.text, { t -> update { s -> s.mapCheck<StepCheck.ScreenJudge>(check.id) { it.copy(text = t) } } },
        enabled = editable, multiline = true, placeholder = "What the screen should look like",
    )
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        AppText("Compare with", color = tc().td, fontSize = 10.sp)
        val options: List<String?> = listOf<String?>(null) + step.examples.map { it.id }
        TestsDropdown(
            selectedLabel = exampleRefLabel(step, check.exampleRef),
            options = options,
            optionLabel = { ref -> exampleRefLabel(step, ref) },
            onSelect = { ref -> update { s -> s.mapCheck<StepCheck.ScreenJudge>(check.id) { it.copy(exampleRef = ref) } } },
            enabled = editable,
            isSelected = { it == check.exampleRef },
        )
    }
}

@Composable
private fun ScriptResultBody(check: StepCheck.ScriptResult, editable: Boolean, update: ((TestStep) -> TestStep) -> StoreResult<*>) {
    val library = LocalTestsUi.current.library
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        TestsBadge(checkKindLabel(check))
        TestsDropdown(
            selectedLabel = library.script(check.scriptId)?.toolName ?: "(missing script)",
            options = library.scripts,
            optionLabel = { it.toolName },
            onSelect = { script -> update { s -> s.mapCheck<StepCheck.ScriptResult>(check.id) { it.copy(scriptId = script.id) } } },
            enabled = editable,
            isSelected = { it.id == check.scriptId },
        )
        AppText("exit code", color = tc().td, fontSize = 10.sp)
        CommitTextField(
            value = check.exitCode?.toString().orEmpty(),
            onCommit = { text ->
                val trimmed = text.trim()
                val code = trimmed.toIntOrNull()
                if (trimmed.isNotEmpty() && code == null) {
                    StoreResult.Invalid("Exit code must be a whole number, or empty for any.")
                } else {
                    update { s -> s.mapCheck<StepCheck.ScriptResult>(check.id) { it.copy(exitCode = code) } }
                }
            },
            modifier = Modifier.width(NUMBER_FIELD_WIDTH),
            enabled = editable,
            placeholder = "any",
        )
    }
    CommitTextField(
        check.stdoutContains.orEmpty(), { t -> update { s -> s.mapCheck<StepCheck.ScriptResult>(check.id) { it.copy(stdoutContains = t.ifEmpty { null }) } } },
        enabled = editable, placeholder = "output must contain (optional)", mono = true,
    )
    KeyValueField(check.args, editable, onCommit = { args -> update { s -> s.mapCheck<StepCheck.ScriptResult>(check.id) { it.copy(args = args) } } },
        placeholder = "Arguments, key=value per line")
}

// ── Examples ─────────────────────────────────────────────────────────

private fun exampleRefLabel(step: TestStep, ref: String?): String {
    if (ref == null) return "(no example)"
    val index = step.examples.indexOfFirst { it.id == ref }
    return if (index < 0) "(missing example)" else exampleLabel(step.examples[index], index)
}

private fun exampleLabel(example: StepExample, index: Int): String {
    val kind = when (example) {
        is StepExample.GoldenScreenshot -> "Screenshot"
        is StepExample.ReferenceLog -> "Log"
    }
    return "${index + 1}. " + example.caption.ifBlank { kind }
}

@Composable
private fun ExamplesEditor(step: TestStep, ops: StepListOps) {
    val ui = LocalTestsUi.current
    val update: ((TestStep) -> TestStep) -> StoreResult<*> = { transform -> ops.update(step.id, transform) }
    val addOptions = buildList {
        add(ExampleKind.REFERENCE_LOG)
        if (ops.assetSuiteId != null) add(ExampleKind.GOLDEN_SCREENSHOT)
    }
    TestsSectionTitle("Examples (${step.examples.size})") {
        if (ops.editable) {
            TestsDropdown(
                selectedLabel = "+ Add example",
                options = addOptions,
                optionLabel = { it.label },
                onSelect = { kind ->
                    when (kind) {
                        ExampleKind.REFERENCE_LOG ->
                            ui.report(update { it.copy(examples = it.examples + StepExample.ReferenceLog(newExampleId(), "")) })
                        ExampleKind.GOLDEN_SCREENSHOT -> ops.assetSuiteId?.let { suiteId ->
                            chooseGoldenImage(ui, suiteId)?.let { name ->
                                ui.report(update { it.copy(examples = it.examples + StepExample.GoldenScreenshot(newExampleId(), name)) })
                            }
                        }
                    }
                },
            )
        }
    }
    if (step.examples.isEmpty()) {
        TestsHint(if (ops.assetSuiteId == null) "None. Shared steps can hold reference logs; screenshots belong to a suite's case steps." else "None.")
    }
    ReorderableColumn(
        items = step.examples,
        idOf = { it.id },
        onMove = { id, to -> ui.report(update { s -> s.copy(examples = s.examples.moveById(id, to) { it.id }) }) },
        reorderEnabled = ops.editable,
        rowGap = 4.dp,
    ) { example, row ->
        ReorderRowCard(row, ops.editable, onRemove = {
            // A screen check that compared against this example must not keep pointing at it.
            ui.report(update { s -> s.withoutExample(example.id) })
            ui.reclaimFocus()
        }) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) { ExampleBody(example, ops, update) }
        }
    }
}

private enum class ExampleKind(val label: String) { REFERENCE_LOG("Reference log"), GOLDEN_SCREENSHOT("Golden screenshot…") }

/** [this] without example [id]; a screen check that referenced it keeps existing but loses the reference. */
internal fun TestStep.withoutExample(id: String): TestStep = copy(
    examples = examples.filterNot { it.id == id },
    checks = checks.map { if (it is StepCheck.ScreenJudge && it.exampleRef == id) it.copy(exampleRef = null) else it },
)

private inline fun <reified E : StepExample> TestStep.mapExample(id: String, transform: (E) -> E): TestStep =
    copy(examples = examples.map { if (it.id == id && it is E) transform(it) else it })

@Composable
private fun ExampleBody(example: StepExample, ops: StepListOps, update: ((TestStep) -> TestStep) -> StoreResult<*>) {
    when (example) {
        is StepExample.ReferenceLog -> {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                TestsBadge("Reference log")
                CommitTextField(
                    example.caption, { c -> update { s -> s.mapExample<StepExample.ReferenceLog>(example.id) { it.copy(caption = c) } } },
                    Modifier.weight(1f), ops.editable, "caption (optional)",
                )
            }
            CommitTextField(
                example.text, { t -> update { s -> s.mapExample<StepExample.ReferenceLog>(example.id) { it.copy(text = t) } } },
                enabled = ops.editable, multiline = true, mono = true, placeholder = "Reference log lines, one per line",
            )
        }
        is StepExample.GoldenScreenshot -> GoldenScreenshotBody(example, ops, update)
    }
}

@Composable
private fun GoldenScreenshotBody(example: StepExample.GoldenScreenshot, ops: StepListOps, update: ((TestStep) -> TestStep) -> StoreResult<*>) {
    val ui = LocalTestsUi.current
    val tc = tc()
    val file = ops.assetSuiteId?.let { ui.state.testGoldenImageFile(it, example.assetPath) }
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        TestsBadge("Golden screenshot")
        CommitTextField(
            example.caption, { c -> update { s -> s.mapExample<StepExample.GoldenScreenshot>(example.id) { it.copy(caption = c) } } },
            Modifier.weight(1f), ops.editable, "caption (optional)",
        )
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        GoldenThumbnail(file)
        Column(Modifier.weight(1f)) {
            AppText(example.assetPath, color = tc.tx, fontSize = 11.sp, fontFamily = MONO, overflow = TextOverflow.Ellipsis)
            if (file == null || !file.isFile) TestsErrorText("The image file is missing from this suite's assets.")
        }
        val suiteId = ops.assetSuiteId
        if (ops.editable && suiteId != null) {
            AppButton("Replace…", onClick = {
                chooseGoldenImage(ui, suiteId)?.let { name ->
                    ui.report(update { s -> s.mapExample<StepExample.GoldenScreenshot>(example.id) { it.copy(assetPath = name) } })
                }
            })
        }
    }
}

/** Asks for an image, copies it into the suite's asset folder and returns its stored name; failures go to the banner. */
private fun chooseGoldenImage(ui: TestsUi, suiteId: String): String? {
    val source = pickOpenFile("Choose a reference screenshot") ?: return null
    ui.reclaimFocus()
    val result = ui.report(ui.state.importTestGoldenImage(suiteId, source))
    return (result as? StoreResult.Ok)?.value
}

@Composable
private fun GoldenThumbnail(file: File?) {
    val tc = tc()
    val bitmap by produceState<ImageBitmap?>(null, file) {
        value = if (file == null || !file.isFile) {
            null
        } else {
            withContext(Dispatchers.IO) {
                decodeBoundedPreviewImage(file)
            }
        }
    }
    Box(Modifier.height(THUMB_HEIGHT).width(THUMB_HEIGHT).background(tc.bg, CORNER_SM).border(1.dp, tc.br, CORNER_SM), contentAlignment = Alignment.Center) {
        bitmap?.let { Image(it, contentDescription = "Reference screenshot", contentScale = ContentScale.Fit, modifier = Modifier.height(THUMB_HEIGHT)) }
            ?: AppText("no preview", color = tc.td, fontSize = 9.sp)
    }
}

@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class, androidx.compose.ui.ExperimentalComposeUiApi::class)

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
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.indagium.testing.device.replayEligibilityProblem
import com.indagium.testing.model.DEFAULT_LOG_ABSENT_FOR_MS
import com.indagium.testing.model.DEFAULT_LOG_WITHIN_MS
import com.indagium.testing.model.DEFAULT_MANUAL_STEP_TIMEOUT_MS
import com.indagium.testing.model.DEFAULT_STEP_TIMEOUT_MS
import com.indagium.testing.model.MAX_STEP_RETRIES
import com.indagium.testing.model.OnFailure
import com.indagium.testing.model.ScriptCall
import com.indagium.testing.model.ScriptPermission
import com.indagium.testing.model.StepCheck
import com.indagium.testing.model.StepExample
import com.indagium.testing.model.StepKind
import com.indagium.testing.model.TestStep
import com.indagium.testing.model.moveById
import com.indagium.testing.model.newCheckId
import com.indagium.testing.model.newExampleId
import com.indagium.testing.model.newStepId
import com.indagium.testing.model.withKind
import com.indagium.testing.store.MAX_STEP_MAX_TOOL_CALLS
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

private val STEP_ROW_HEIGHT = 34.dp
private val STEP_NUMBER_WIDTH = 20.dp
private val STEP_MENU_SLOT = 26.dp
private val CHECK_DOT_SIZE = 7.dp
private const val UNTITLED_STEP = "Untitled step"

/**
 * The step list. [header] replaces the default "Steps (N)" title with a "+ Add step" button (the case screen passes its own
 * header with the authoring menu); without it the list draws the default, which is what shared steps use.
 */
@Composable
internal fun StepListSection(ops: StepListOps, header: (@Composable () -> Unit)? = null) {
    val ui = LocalTestsUi.current
    val view = ui.view
    val ids = ops.steps.map { it.id }
    val selectedId = effectiveSelectedStepId(ops.steps, view.selectedStepId)
    // A step is always selected while there are steps (the inspector is never empty): a case opened without one selects its first.
    LaunchedEffect(ids, view.selectedStepId) {
        if (selectedId != null && selectedId != view.selectedStepId) view.selectedStepId = selectedId
    }
    TestsKeyTargetEffect { event -> handleStepKey(ui, ops, event) }
    if (header != null) {
        header()
    } else {
        TestsSectionTitle("Steps (${ops.steps.size})") {
            if (ops.editable) AppButton("+ Add step", onClick = { addBlankStep(ui, ops) }, variant = ButtonVariant.Ghost)
        }
    }
    if (ops.steps.isEmpty()) TestsHint("No steps yet. A step is one thing the agent does, with what you expect to see afterwards.")
    ReorderableColumn(
        items = ops.steps,
        idOf = { it.id },
        onMove = { id, to -> ui.report(ops.move(id, to)) },
        fixedRowHeight = STEP_ROW_HEIGHT,
        reorderEnabled = ops.editable,
    ) { step, row -> StepRow(step, row, ops, selected = step.id == selectedId) }
    if (ops.steps.size > 1) TestsHint("↑ ↓ select a step" + if (ops.editable) " · ⌥↑ ⌥↓ move it" else "", Modifier.padding(top = 6.dp))
}

/** Appends a blank step, selects it and asks for the cursor in its Action field. */
internal fun addBlankStep(ui: TestsUi, ops: StepListOps) {
    val step = TestStep(newStepId(), NEW_STEP_ACTION)
    if (ui.report(ops.add(step)) is StoreResult.Ok) {
        ui.view.selectStep(step.id)
        ui.view.revealStepId = step.id
        ui.view.focusStepActionId = step.id
    }
}

/** Esc: closes the case-field editor in the inspector; false (key left alone) when none is open. */
private fun closeCaseFieldEditor(ui: TestsUi): Boolean {
    if (ui.view.caseInspectorField == null) return false
    ui.view.caseInspectorField = null
    ui.reclaimFocus()
    return true
}

/**
 * Handles the bare keys of a step list (registered through [TestsKeyTargetEffect]): ↑/↓ select the previous/next step and
 * ⌥↑/⌥↓ move the selected one, Esc closes an open case-field editor. Moving is refused (the key is left alone) when the list
 * is read-only.
 */
private fun handleStepKey(ui: TestsUi, ops: StepListOps, event: KeyEvent): Boolean {
    val view = ui.view
    if (event.key == Key.Escape) return closeCaseFieldEditor(ui)
    val selected = effectiveSelectedStepId(ops.steps, view.selectedStepId)
    altArrowDirection(event)?.let { up ->
        if (!ops.editable || selected == null) return false
        reorderAltTarget(ops.steps.map { it.id }, selected, up)?.let { to ->
            ui.report(ops.move(selected, to))
            view.revealStepId = selected
        }
        return true
    }
    if (event.isAltPressed || event.isCtrlPressed || event.isMetaPressed || event.isShiftPressed) return false
    val delta = when (event.key) {
        Key.DirectionUp -> -1
        Key.DirectionDown -> 1
        else -> null
    }
    val next = delta?.let { stepSelectionMovedBy(ops.steps, selected, it) } ?: return false
    view.selectStep(next)
    view.revealStepId = next
    // A clicked row keeps keyboard focus (and draws its focus border); hand it back so the arrows keep working from the root.
    ui.reclaimFocus()
    return true
}

/** The ⋯ menu of a step, shared by its list row and the inspector header. */
private fun stepMenuItems(ui: TestsUi, ops: StepListOps, step: TestStep): List<TestsMenuItem> {
    val index = ops.steps.indexOfFirst { it.id == step.id }
    val readOnlyHint = "This list is read-only."
    return listOf(
        TestsMenuItem(
            "Move up", onClick = { ui.report(ops.move(step.id, index - 1)) }, kbd = "⌥↑",
            enabled = ops.editable && index > 0, disabledHint = readOnlyHint.takeIf { !ops.editable },
        ),
        TestsMenuItem(
            "Move down", onClick = { ui.report(ops.move(step.id, index + 1)) }, kbd = "⌥↓",
            enabled = ops.editable && index in 0 until ops.steps.lastIndex, disabledHint = readOnlyHint.takeIf { !ops.editable },
        ),
        TestsMenuItem(
            "Duplicate", onClick = { ui.report(ops.duplicate(step.id)) },
            enabled = ops.editable, disabledHint = readOnlyHint,
        ),
        TestsMenuItem(
            "Delete", onClick = { deleteStep(ui, ops, step) }, danger = true,
            enabled = ops.editable, disabledHint = readOnlyHint, dividerBefore = true,
        ),
    )
}

/** Deletes [step] at once (like the × always did); when it was the selected one its neighbour becomes selected. */
private fun deleteStep(ui: TestsUi, ops: StepListOps, step: TestStep) {
    val view = ui.view
    val wasSelected = effectiveSelectedStepId(ops.steps, view.selectedStepId) == step.id
    val neighbour = stepSelectionAfterDelete(ops.steps, step.id)
    if (ui.report(ops.delete(step.id)) is StoreResult.Ok && wasSelected) view.selectedStepId = neighbour
    ui.reclaimFocus()
}

@Composable
private fun StepRow(step: TestStep, row: ReorderRowScope, ops: StepListOps, selected: Boolean) {
    val tc = tc()
    val ui = LocalTestsUi.current
    val view = ui.view
    var hovered by remember { mutableStateOf(false) }
    val revealer = remember { BringIntoViewRequester() }
    LaunchedEffect(view.revealStepId) {
        if (view.revealStepId == step.id) {
            revealer.bringIntoView()
            view.revealStepId = null
        }
    }
    TestsListRow(
        selected = selected,
        // Handing the keyboard focus back first makes a field of the step being left commit before the inspector switches.
        onClick = {
            ui.reclaimFocus()
            view.selectStep(step.id)
        },
        modifier = Modifier.bringIntoViewRequester(revealer)
            .onPointerEvent(PointerEventType.Enter) { hovered = true }
            .onPointerEvent(PointerEventType.Exit) { hovered = false },
        minHeight = STEP_ROW_HEIGHT,
        startPadding = 4.dp,
        spacing = 6.dp,
    ) {
        // The ⋯ keeps its slot while hidden so the other columns never shift; the grip is always visible.
        ReorderGrip(row)
        AppText("${row.index + 1}", color = tc.td, fontSize = 10.sp, fontFamily = MONO, modifier = Modifier.width(STEP_NUMBER_WIDTH))
        AppText(
            step.action.ifBlank { UNTITLED_STEP },
            color = if (step.action.isBlank()) tc.td else tc.tx,
            fontSize = 12.sp,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        TestsBadge(stepKindLabel(step.kind))
        val screenCount = step.examples.count { it is StepExample.GoldenScreenshot }
        if (screenCount > 0) TestsBadge(if (screenCount == 1) "Screen" else "Screens $screenCount")
        stepChecksSummary(step)?.let { summary -> StepChecksSummary(step, summary) }
        Box(Modifier.size(STEP_MENU_SLOT).alpha(if (hovered || selected) 1f else 0f)) {
            TestsOverflowMenu(stepMenuItems(ui, ops, step), contentDescription = "Step ${row.index + 1} actions")
        }
    }
}

/** A dot and "Screen" / "3 checks"; hovering lists every check kind. */
@Composable
private fun StepChecksSummary(step: TestStep, summary: String) {
    val tc = tc()
    HintWhen(true, stepCheckBadges(step).joinToString(", ")) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Box(Modifier.size(CHECK_DOT_SIZE).border(1.5.dp, tc.ac, CircleShape))
            AppText(summary, color = tc.ts, fontSize = 10.sp)
        }
    }
}

// ── Step inspector ───────────────────────────────────────────────────

/**
 * The editor of the selected step, for the inspector pane (the case and shared-step screens): a header
 * "Step N of M" with the step's ⋯ menu, then [StepEditor]. Shows a hint while no step is selected.
 */
@Composable
internal fun SelectedStepInspector(ops: StepListOps) {
    val ui = LocalTestsUi.current
    val selectedId = effectiveSelectedStepId(ops.steps, ui.view.selectedStepId)
    val step = ops.steps.firstOrNull { it.id == selectedId }
    if (step == null) {
        TestsHint(if (ops.steps.isEmpty()) "No steps yet. Add one to edit it here." else "Select a step to edit it.")
        return
    }
    // Keyed by step so a draft typed into one step's field can never be committed to the next one.
    key(step.id) {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            TestsInspectorHeader(
                "Step ${ops.steps.indexOf(step) + 1} of ${ops.steps.size}",
                step.action.ifBlank { UNTITLED_STEP },
                overflowItems = stepMenuItems(ui, ops, step),
            )
            StepScreenshotStrip(step, ops)
            RecordedScreensPanel(step, ops)
            StepEditor(step, ops)
        }
    }
}

// ── Screenshots shown without opening anything ───────────────────────

private val STRIP_THUMB_SIZE = 48.dp
private val RECORDED_THUMB_SIZE = 96.dp
private const val STRIP_MAX_THUMBS = 8

/**
 * A row of small thumbnails of the step's golden screenshots, under the inspector title, so the screenshots are visible
 * without opening the Examples section. Clicking one opens that section and scrolls it into view.
 */
@Composable
private fun StepScreenshotStrip(step: TestStep, ops: StepListOps) {
    val ui = LocalTestsUi.current
    val suiteId = ops.assetSuiteId ?: return
    val shots = step.examples.filterIsInstance<StepExample.GoldenScreenshot>()
    if (shots.isEmpty()) return
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
        itemVerticalAlignment = Alignment.CenterVertically,
    ) {
        AppText("Screens", color = tc().td, fontSize = 10.sp)
        shots.take(STRIP_MAX_THUMBS).forEachIndexed { index, shot ->
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                GoldenThumbnail(
                    ui.state.testGoldenImageFile(suiteId, shot.assetPath),
                    size = STRIP_THUMB_SIZE,
                    modifier = Modifier.clip(CORNER_SM).clickable {
                        ui.view.setExamplesExpanded(step.id, true)
                        ui.view.examplesRevealStepId = step.id
                        ui.reclaimFocus()
                    }.semantics { contentDescription = "Screenshot ${index + 1} of ${shots.size}. Show in Examples" },
                )
                AppText(when (shot.role) {
                    com.indagium.testing.model.ScreenshotRole.EXPECTED -> "Expected"
                    com.indagium.testing.model.ScreenshotRole.RECORDED_BEFORE -> "Before ref"
                    com.indagium.testing.model.ScreenshotRole.RECORDED_AFTER -> "After ref"
                }, color = tc().td, fontSize = 9.sp)
            }
        }
        if (shots.size > STRIP_MAX_THUMBS) TestsHint("+${shots.size - STRIP_MAX_THUMBS} more")
    }
}

/** A recorded screen read off the disk: its caption and the decoded image (null while it cannot be decoded). */
private class RecordedScreenImage(val caption: String, val bitmap: ImageBitmap?)

/**
 * "Recorded screens": the before / after screenshots stored with the recording the step came from ("Keep recording evidence with
 * recorded cases"). Read and decoded off the UI thread, bounded like every other preview; shows nothing when the step has no
 * recording or the evidence has no image for it.
 */
@Composable
private fun RecordedScreensPanel(step: TestStep, ops: StepListOps) {
    val ui = LocalTestsUi.current
    val tc = tc()
    val suiteId = ops.assetSuiteId
    val recordingId = step.recordingRef
    if (suiteId == null || recordingId == null) return
    val images by produceState<List<RecordedScreenImage>>(emptyList(), suiteId, recordingId, step.id) {
        value = withContext(Dispatchers.IO) {
            val screens = ui.state.recordedStepScreens(suiteId, recordingId, step.id) ?: return@withContext emptyList()
            buildList {
                screens.before?.let { add(RecordedScreenImage("Before", decodeBoundedPreviewImage(it))) }
                screens.after?.let { add(RecordedScreenImage("After", decodeBoundedPreviewImage(it))) }
                if (screens.before == null && screens.after == null) {
                    screens.input?.let { add(RecordedScreenImage("Input", decodeBoundedPreviewImage(it))) }
                }
            }
        }
    }
    if (images.isEmpty()) return
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        TestsPanelTitle("Recorded screens")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            images.forEach { image ->
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Box(
                        Modifier.size(RECORDED_THUMB_SIZE).background(tc.bg, CORNER_SM).border(1.dp, tc.br, CORNER_SM),
                        contentAlignment = Alignment.Center,
                    ) {
                        image.bitmap?.let {
                            Image(
                                it, contentDescription = "Recorded screen: ${image.caption}",
                                contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize(),
                            )
                        } ?: AppText("no preview", color = tc.td, fontSize = 10.sp)
                    }
                    AppText(image.caption, color = tc.td, fontSize = 10.sp)
                }
            }
        }
    }
}

// ── Step editor ──────────────────────────────────────────────────────

@Composable
private fun StepEditor(step: TestStep, ops: StepListOps) {
    val ui = LocalTestsUi.current
    val update: ((TestStep) -> TestStep) -> StoreResult<*> = { transform -> ops.update(step.id, transform) }
    var configuringOptional by remember(step.id, step.optional) { mutableStateOf(false) }
    var optionalCondition by remember(step.id, step.condition) { mutableStateOf(step.condition.orEmpty()) }
    val actionFocus = remember { FocusRequester() }
    // A step that was just added gets the cursor in its Action field.
    LaunchedEffect(step.id, ui.view.focusStepActionId) {
        if (ui.view.focusStepActionId == step.id) {
            runCatching { actionFocus.requestFocus() }
            ui.view.focusStepActionId = null
        }
    }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        StepKindSelector(step.kind, ops.editable, replayAvailable = step.replayEligibilityProblem() == null) { kind -> changeStepKind(ui, step, kind, update) }
        TestsHint(stepKindHint(step.kind))
        TestsLabeled(if (step.kind == StepKind.MANUAL) "Instructions for the tester" else "Action") {
            CommitTextField(
                step.action, { text -> update { it.copy(action = text) } },
                modifier = Modifier.focusRequester(actionFocus),
                enabled = ops.editable, multiline = true,
                placeholder = when (step.kind) {
                    StepKind.AGENT -> "What the agent should do"
                    StepKind.MANUAL -> "What the tester should do, such as plug the USB stick into the phone"
                    StepKind.SCRIPT -> "What the script does (shown in the report)"
                    StepKind.REPLAY -> "What the recorded input does (shown in the report)"
                },
            )
        }
        StepKindFields(step, ops, update)
        TestsLabeled("Expected result") {
            CommitTextField(
                step.expected, { text -> update { it.copy(expected = text) } },
                enabled = ops.editable, multiline = true,
                placeholder = if (step.optional) "What should be true when this optional action runs" else "What should be true afterwards",
            )
        }
        if (step.kind == StepKind.AGENT) {
            OptionalStepEditor(step, ops, update, configuringOptional, optionalCondition, { configuringOptional = it }, { optionalCondition = it })
        }
        ChecksEditor(step, ops)
        ExamplesEditor(step, ops)
        StepSettings(step, ops)
    }
}

/** Switches the step's kind. A script step needs a script, so one is chosen (or created) as part of the switch. */
private fun changeStepKind(ui: TestsUi, step: TestStep, kind: StepKind, update: ((TestStep) -> TestStep) -> StoreResult<*>) {
    if (kind != StepKind.SCRIPT || step.script != null) {
        ui.report(update { it.withKind(kind) })
        return
    }
    val existing = ui.library.scripts.firstOrNull { it.permission != ScriptPermission.SETUP_TEARDOWN_ONLY }
    val script = existing ?: createPlaceholderScript(ui) ?: return
    val result = ui.report(update { it.withKind(kind).copy(script = ScriptCall(script.id, defaultArgsFor(script))) })
    if (existing == null && result is StoreResult.Ok) ui.view.openScriptEditorFor(script.id, step.id)
}

@Composable
private fun StepKindFields(step: TestStep, ops: StepListOps, update: ((TestStep) -> TestStep) -> StoreResult<*>) {
    val ui = LocalTestsUi.current
    val addScript: () -> Unit = {
        createScriptForStep(ui, step.id)?.let { script -> ui.report(update { it.copy(script = ScriptCall(script.id, defaultArgsFor(script))) }) }
    }
    when (step.kind) {
        StepKind.AGENT -> Unit
        StepKind.REPLAY -> step.recordedInput?.let { TestsHint(recordedInputSummary(it)) }
        StepKind.SCRIPT -> TestsLabeled("Script to run") {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                ScriptCallEditor(step.script, ops.editable, optional = false, onChange = { call -> update { it.copy(script = call) } }, onNewScript = addScript)
            }
        }
        StepKind.MANUAL -> ManualStepFields(
            completion = step.manualCompletion,
            script = step.script,
            enabled = ops.editable,
            onCompletion = { mode -> ui.report(update { it.copy(manualCompletion = mode) }) },
            onScript = { call -> update { it.copy(script = call) } },
            onNewScript = addScript,
        )
    }
}

@Suppress("LongParameterList") // The optional-step controls keep their draft state with the step editor that owns it.
@Composable
private fun OptionalStepEditor(
    step: TestStep,
    ops: StepListOps,
    update: ((TestStep) -> TestStep) -> StoreResult<*>,
    configuringOptional: Boolean,
    optionalCondition: String,
    onConfiguring: (Boolean) -> Unit,
    onCondition: (String) -> Unit,
) {
    val ui = LocalTestsUi.current
    CheckRow(checked = step.optional || configuringOptional, onToggle = {
        if (step.optional) {
            update { it.copy(optional = false, condition = null) }
        } else {
            onConfiguring(!configuringOptional)
            onCondition(step.condition.orEmpty())
        }
    }, enabled = ops.editable) {
        AppText("Optional: skip when its condition is absent", color = tc().ts, fontSize = 11.sp)
    }
    if (step.optional) {
        TestsLabeled("Perform only when") {
            CommitTextField(
                step.condition.orEmpty(), { text -> update { it.copy(condition = text) } },
                enabled = ops.editable, multiline = true, placeholder = "Describe the visible condition, such as an ad is playing",
            )
        }
    } else if (configuringOptional) {
        TestsLabeled("Perform only when") {
            CommitTextField(optionalCondition, { text -> onCondition(text); StoreResult.Ok(Unit) }, enabled = ops.editable,
                multiline = true, placeholder = "Describe the visible condition, such as an ad is playing")
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AppButton("Save condition", enabled = ops.editable && optionalCondition.isNotBlank(), onClick = {
                val result = update { it.copy(optional = true, condition = optionalCondition.trim()) }
                ui.report(result)
                if (result is StoreResult.Ok) onConfiguring(false)
            }, variant = ButtonVariant.Secondary)
            AppButton("Cancel", onClick = { onConfiguring(false) }, variant = ButtonVariant.Ghost)
        }
    }
}

private val ON_FAILURE_LABELS = mapOf(
    OnFailure.STOP_CASE to "Stop the case",
    OnFailure.CONTINUE to "Continue",
    OnFailure.CREATE_ISSUE_AND_CONTINUE to "Create issue, continue",
    OnFailure.PAUSE_FOR_USER to "Pause for me",
)

/**
 * The one line shown under a collapsed "Advanced" header, built from the fields inside it: "retries 1 · up to 40 tool
 * calls · on failure: stop the case". Fields the step's kind does not have are left out, and a timeout only shows when it is
 * not the kind's default.
 */
internal fun stepAdvancedSummary(step: TestStep): String = buildList {
    if (step.kind != StepKind.MANUAL) add("retries ${step.retries}")
    if (step.kind == StepKind.AGENT) add("up to ${step.maxToolCalls} tool calls")
    add("on failure: ${ON_FAILURE_LABELS.getValue(step.onFailure).lowercase()}")
    val defaultTimeout = if (step.kind == StepKind.MANUAL) DEFAULT_MANUAL_STEP_TIMEOUT_MS else DEFAULT_STEP_TIMEOUT_MS
    if (step.timeoutMs != defaultTimeout) add("${if (step.kind == StepKind.MANUAL) "waits up to" else "timeout"} ${formatDuration(step.timeoutMs)}")
}.joinToString(" · ")

/** A collapsible section header of the step inspector; toggling hands keyboard focus back to the workspace. */
@Composable
private fun InspectorDisclosure(
    title: String,
    expanded: Boolean,
    onToggle: () -> Unit,
    trailing: (@Composable RowScope.() -> Unit)? = null,
) {
    val ui = LocalTestsUi.current
    SectionHeader(title, trailing = trailing, expanded = expanded, onToggle = {
        onToggle()
        ui.reclaimFocus()
    })
}

@Composable
private fun StepSettings(step: TestStep, ops: StepListOps) {
    val view = LocalTestsUi.current.view
    val update: ((TestStep) -> TestStep) -> StoreResult<*> = { transform -> ops.update(step.id, transform) }
    val expanded = view.stepAdvancedExpanded
    InspectorDisclosure("Advanced", expanded, onToggle = { view.stepAdvancedExpanded = !expanded })
    if (!expanded) {
        TestsHint(
            stepAdvancedSummary(step), Modifier.padding(horizontal = 12.dp), maxLines = 2,
        )
        return
    }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        TestsLabeled("When it fails") {
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
        TestsLabeled(if (step.kind == StepKind.MANUAL) "Wait up to (ms)" else "Timeout (ms)", Modifier.width(NUMBER_FIELD_WIDTH)) {
            CommitNumberField(step.timeoutMs, 1L, MAX_STEP_TIMEOUT_MS, { n -> update { it.copy(timeoutMs = n) } }, enabled = ops.editable)
        }
        // A manual step is not retried (the tester would only wait again) and only an AI agent spends tool calls.
        if (step.kind != StepKind.MANUAL) {
            TestsLabeled("Retries", Modifier.width(NUMBER_FIELD_WIDTH)) {
                CommitNumberField(
                    step.retries.toLong(), 0L, MAX_STEP_RETRIES.toLong(), { n -> update { it.copy(retries = n.toInt()) } }, enabled = ops.editable,
                )
            }
        }
        if (step.kind == StepKind.AGENT) {
            TestsLabeled("Max tool calls", Modifier.width(NUMBER_FIELD_WIDTH)) {
                CommitNumberField(
                    step.maxToolCalls.toLong(), MIN_STEP_MAX_TOOL_CALLS.toLong(), MAX_STEP_MAX_TOOL_CALLS.toLong(),
                    { n -> update { it.copy(maxToolCalls = n.toInt()) } }, enabled = ops.editable,
                )
            }
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
    DEVICE_STATE("Device state"),
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
    CheckKind.DEVICE_STATE -> newDeviceStateCheck()
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
        is StepCheck.DeviceState -> DeviceStateCheckBody(check, editable) { changed -> update { s -> s.mapCheck<StepCheck.DeviceState>(check.id) { changed } } }
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
    // Wraps in a narrow inspector: the badge and the duration share a line, the tag gets a line of its own.
    WrapRow {
        TestsBadge(badge)
        AppText(durationLabel, color = tc().td, fontSize = 10.sp)
        CommitNumberField(durationMs, 1L, MAX_STEP_TIMEOUT_MS, onDuration, Modifier.width(NUMBER_FIELD_WIDTH), editable)
    }
    CommitTextField(tag.orEmpty(), { onTag(it.trim().ifEmpty { null }) }, enabled = editable, placeholder = "tag (optional)", mono = true)
    CommitTextField(regex, onRegex, enabled = editable, placeholder = "regular expression", mono = true)
}

@Composable
private fun ScreenJudgeBody(check: StepCheck.ScreenJudge, step: TestStep, editable: Boolean, update: ((TestStep) -> TestStep) -> StoreResult<*>) {
    TestsBadge(checkKindLabel(check))
    CommitTextField(
        check.text, { t -> update { s -> s.mapCheck<StepCheck.ScreenJudge>(check.id) { it.copy(text = t) } } },
        enabled = editable, multiline = true, placeholder = "What the screen should look like",
    )
    WrapRow {
        AppText("Compare with", color = tc().td, fontSize = 10.sp)
        val options: List<String?> = listOf<String?>(null) + step.examples
            .filter { it !is StepExample.GoldenScreenshot || it.role == com.indagium.testing.model.ScreenshotRole.EXPECTED }
            .map { it.id }
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
    WrapRow {
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
        is StepExample.GoldenScreenshot -> when (example.role) {
            com.indagium.testing.model.ScreenshotRole.EXPECTED -> "Expected screenshot"
            com.indagium.testing.model.ScreenshotRole.RECORDED_BEFORE -> "Before reference"
            com.indagium.testing.model.ScreenshotRole.RECORDED_AFTER -> "After reference"
        }
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
    val view = ui.view
    val expanded = view.examplesExpanded(step.id, step.examples.isNotEmpty())
    val revealer = remember { BringIntoViewRequester() }
    LaunchedEffect(view.examplesRevealStepId) {
        if (view.examplesRevealStepId == step.id) {
            revealer.bringIntoView()
            view.examplesRevealStepId = null
        }
    }
    Box(Modifier.fillMaxWidth().bringIntoViewRequester(revealer)) {
        InspectorDisclosure(
            "Examples (${step.examples.size})", expanded,
            onToggle = { view.setExamplesExpanded(step.id, !expanded) },
        ) {
            if (expanded && ops.editable) {
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
    }
    if (!expanded) return
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
        TestsBadge(when (example.role) {
            com.indagium.testing.model.ScreenshotRole.EXPECTED -> "Golden screenshot"
            com.indagium.testing.model.ScreenshotRole.RECORDED_BEFORE -> "Recorded before"
            com.indagium.testing.model.ScreenshotRole.RECORDED_AFTER -> "Recorded after"
        })
        CommitTextField(
            example.caption, { c -> update { s -> s.mapExample<StepExample.GoldenScreenshot>(example.id) { it.copy(caption = c) } } },
            Modifier.weight(1f), ops.editable, "caption (optional)",
        )
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        GoldenThumbnail(file)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            AppText(example.assetPath, color = tc.tx, fontSize = 11.sp, fontFamily = MONO, overflow = TextOverflow.Ellipsis)
            if (file == null || !file.isFile) TestsErrorText("The image file is missing from this suite's assets.")
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
}

/** Asks for an image, copies it into the suite's asset folder and returns its stored name; failures go to the banner. */
private fun chooseGoldenImage(ui: TestsUi, suiteId: String): String? {
    val source = pickOpenFile("Choose a reference screenshot") ?: return null
    ui.reclaimFocus()
    val result = ui.report(ui.state.importTestGoldenImage(suiteId, source))
    return (result as? StoreResult.Ok)?.value
}

/** The golden screenshot [file] fitted into a square of [size] (a placeholder while it is missing or decoding). */
@Composable
internal fun GoldenThumbnail(file: File?, size: Dp = THUMB_HEIGHT, modifier: Modifier = Modifier) {
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
    Box(modifier.size(size).background(tc.bg, CORNER_SM).border(1.dp, tc.br, CORNER_SM), contentAlignment = Alignment.Center) {
        bitmap?.let { Image(it, contentDescription = "Reference screenshot", contentScale = ContentScale.Fit, modifier = Modifier.height(size)) }
            ?: AppText("no preview", color = tc.td, fontSize = 10.sp)
    }
}

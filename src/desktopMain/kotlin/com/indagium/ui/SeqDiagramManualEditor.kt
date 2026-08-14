@file:OptIn(
    androidx.compose.foundation.ExperimentalFoundationApi::class,
    androidx.compose.ui.ExperimentalComposeUiApi::class,
)
@file:Suppress("MaxLineLength")

package com.indagium.ui

import androidx.compose.foundation.*
import androidx.compose.foundation.TooltipArea
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.*
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import com.indagium.diagram.DiagramParameter
import com.indagium.diagram.DiagramParticipant
import com.indagium.diagram.GuidedTargetPassState
import com.indagium.diagram.ManualDiagramActivation
import com.indagium.diagram.ManualDiagramDocument
import com.indagium.diagram.ManualDiagramEvidence
import com.indagium.diagram.ManualDiagramGroup
import com.indagium.diagram.ManualDiagramInteraction
import com.indagium.diagram.ManualDiagramMessageDefinition
import com.indagium.diagram.ManualDiagramNote
import com.indagium.diagram.ManualDiagramRepeatPresentation
import com.indagium.diagram.ManualDiagramSeedConfiguration
import com.indagium.diagram.ManualFragmentKind
import com.indagium.diagram.ManualInteractionAuthoring
import com.indagium.diagram.ManualMessageBulkAction
import com.indagium.diagram.ManualMessageFilter
import com.indagium.diagram.ManualMessageMatch
import com.indagium.diagram.ManualMessageQueueRow
import com.indagium.diagram.ManualMessageSort
import com.indagium.diagram.ManualMessageState
import com.indagium.diagram.ManualMessageStateKind
import com.indagium.diagram.ManualMessageVisibility
import com.indagium.diagram.ManualOperationVisibility
import com.indagium.diagram.ManualRegenerationChangeKind
import com.indagium.diagram.ManualRegenerationReview
import com.indagium.diagram.ManualRegenerationReviewRow
import com.indagium.diagram.ManualRegenerationRowDecision
import com.indagium.diagram.MessageKind
import com.indagium.diagram.ParticipantKind
import com.indagium.diagram.SeqDiagramSpec
import com.indagium.diagram.acceptAllRegenerationRows
import com.indagium.diagram.advanceGuidedTargetPass
import com.indagium.diagram.applyManualMessageBulkAction
import com.indagium.diagram.beginGuidedTargetPass
import com.indagium.diagram.buildManualMessageQueue
import com.indagium.diagram.groupManualMessageQueueRows
import com.indagium.diagram.guidedTargetContext
import com.indagium.diagram.guidedTargetRow
import com.indagium.diagram.manualMessageDisplayTemplate
import com.indagium.diagram.manualEvidenceEntryIds
import com.indagium.diagram.manualInteractionIdsForSelectedMessages
import com.indagium.diagram.manualMessageTemplate
import com.indagium.diagram.manualMessageRepeatPolicy
import com.indagium.diagram.manualMessageRepeatPresentation
import com.indagium.diagram.matchManualMessage
import com.indagium.diagram.normalizeManualDocument
import com.indagium.diagram.rejectAllRegenerationRows
import com.indagium.diagram.selectManualQueueMessageIds
import com.indagium.diagram.setManualMessageTargetForOccurrences
import com.indagium.diagram.suggestManualTarget
import com.indagium.diagram.unlockManualInteraction
import com.indagium.diagram.withRowDecision
import com.indagium.model.LogEntry
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

private val SEQUENCE_EDITOR_ROW_HEIGHT = 36.dp

// Shared 1-9 index→Key mapping: originally inlined in GuidedTargetPassCard's onKeyEvent, factored
// out so ManualMessageQueueEditor's own workspace-local keyboard map (Stage 4) picks lifelines by
// the same digit ordering instead of drifting into a second, subtly different list.
private val MANUAL_DIGIT_KEYS = listOf(
    Key.One, Key.Two, Key.Three, Key.Four, Key.Five,
    Key.Six, Key.Seven, Key.Eight, Key.Nine,
)

/**
 * Editor controls deliberately update only durable [SeqDiagramSpec] fields. Preview rebuilding is
 * handled by SeqDiagramCoordinator's latest-only lane, so typing in this panel never runs the
 * builder on the composition thread.
 */
@Composable
// `preview` is plumbed from the caller's actual preview state but not yet read in this body —
// flagged during a detekt cleanup pass rather than guessed at; kept (not deleted) since the call
// site already threads a real value through.
@Suppress("UnusedParameter")
internal fun DiagramAuthoringSection(
    spec: SeqDiagramSpec,
    lifelineIds: List<String>,
    preview: com.indagium.diagram.SeqDiagram?,
    entries: List<LogEntry>,
    anchorEntryIds: Set<Int>,
    workspaceKey: String,
    rangeContent: @Composable () -> Unit,
    onSpec: (SeqDiagramSpec) -> Unit,
    onApplySeed: (ManualDiagramSeedConfiguration, Boolean) -> Unit,
    onRevertSeed: () -> Unit,
    onClearAllManual: () -> Unit,
    onNavigateEvidence: (Int) -> Unit,
    canOpenSourceEvidence: (Int) -> Boolean,
    onOpenSourceEvidence: (Int) -> Unit,
    focusedManualInteractionId: String?,
    onFocusManualInteraction: (String?) -> Unit,
    hoveredManualInteractionId: String?,
    onHoverManualInteraction: (String?) -> Unit,
    manualSeedReview: ManualRegenerationReview?,
    onUpdateSeedReview: (ManualRegenerationReview) -> Unit,
    onAcceptSeedReview: () -> Unit,
    onCancelSeedReview: () -> Unit,
    canRevertSeed: Boolean,
    seedNeedsConfirmation: Boolean,
    seedBusy: Boolean,
    seedStatus: String?,
) {
    var seedConfiguration by remember(workspaceKey) { mutableStateOf(ManualDiagramSeedConfiguration()) }
    var confirmApply by remember(workspaceKey) { mutableStateOf(false) }
    var guidedPass by remember(workspaceKey) { mutableStateOf<GuidedTargetPassState?>(null) }
    var showAdvancedStructure by remember(workspaceKey) { mutableStateOf(false) }
    // Stage 3: the seed/scope controls (range, source options, review/reset, the regeneration
    // review) used to render inline here as permanent furniture; they now live in a dialog opened
    // only from the message queue's own footer "Regenerate…" button (mockup §08 — "scope controls
    // belong in the regenerate sheet, not permanent furniture"). Pure layout move: every callback
    // below is wired identically to before, just from inside RegenerateDialogContent instead of
    // this composable's own body.
    var showRegenerateDialog by remember(workspaceKey) { mutableStateOf(false) }

    var selectedMessageIds by remember(workspaceKey) { mutableStateOf<Set<String>>(emptySet()) }
    var selectionAnchorMessageId by remember(workspaceKey) { mutableStateOf<String?>(null) }
    LaunchedEffect(focusedManualInteractionId, spec.manualDocument.interactions, spec.manualDocument.messages) {
        if (focusedManualInteractionId != null) {
            val row = groupManualMessageQueueRows(spec.manualDocument).firstOrNull { candidate ->
                candidate.id == focusedManualInteractionId || focusedManualInteractionId in candidate.interactionIds
            }
            if (row != null) {
                selectedMessageIds = setOf(row.id)
                selectionAnchorMessageId = row.id
            }
        }
    }
    if (guidedPass == null) {
        ManualMessageQueueEditor(
            spec, lifelineIds, entries, anchorEntryIds, workspaceKey, selectedMessageIds,
            selectionAnchorMessageId,
            onSelectionChanged = { ids, anchor -> selectedMessageIds = ids; selectionAnchorMessageId = anchor },
            onSpec = onSpec,
            onFixTargets = { guidedPass = beginGuidedTargetPass(spec.manualDocument) },
            onNavigateEvidence = onNavigateEvidence,
            canOpenSourceEvidence = canOpenSourceEvidence,
            onOpenSourceEvidence = onOpenSourceEvidence,
            focusedManualInteractionId = focusedManualInteractionId,
            onFocusManualInteraction = onFocusManualInteraction,
            hoveredManualInteractionId = hoveredManualInteractionId,
            onHoverManualInteraction = onHoverManualInteraction,
            onOpenRegenerateDialog = { showRegenerateDialog = true },
        )
    } else {
        GuidedTargetPassCard(
            spec = spec,
            lifelineIds = lifelineIds,
            entries = entries,
            state = guidedPass!!,
            onExit = { guidedPass = null },
            onNavigateEvidence = onNavigateEvidence,
            onChooseTarget = { targetId, kind, applyToAllOccurrences ->
                val currentState = guidedPass ?: return@GuidedTargetPassCard
                val row = guidedTargetRow(spec.manualDocument, currentState) ?: return@GuidedTargetPassCard
                val nextDocument = setManualMessageTargetForOccurrences(
                    document = spec.manualDocument,
                    messageId = row.id,
                    occurrenceIds = if (applyToAllOccurrences) row.interactionIds.toSet() else setOf(row.representative.id),
                    targetParticipantId = targetId,
                    kind = kind,
                )
                onSpec(spec.copy(manualDocument = nextDocument))
                guidedPass = advanceGuidedTargetPass(nextDocument, currentState)
            },
            onSkip = {
                guidedPass = guidedPass?.let { advanceGuidedTargetPass(spec.manualDocument, it) }
            },
            onCreateLifeline = { id, applyToAllOccurrences ->
                val clean = id.trim()
                if (clean.isNotEmpty() && lifelineIds.none { it == clean }) {
                    val currentState = guidedPass
                    val row = currentState?.let { guidedTargetRow(spec.manualDocument, it) }
                    val targetDocument = row?.let { targetRow ->
                        val kind = if (targetRow.fromParticipantId == clean) MessageKind.SELF else MessageKind.CALL
                        setManualMessageTargetForOccurrences(
                            document = spec.manualDocument,
                            messageId = targetRow.id,
                            occurrenceIds = if (applyToAllOccurrences) targetRow.interactionIds.toSet() else setOf(targetRow.representative.id),
                            targetParticipantId = clean,
                            kind = kind,
                        )
                    }
                    onSpec(
                        spec.copy(
                            components = spec.components + com.indagium.diagram.DiagramComponent(
                                id = clean,
                                displayName = clean,
                                tagIds = setOf(clean),
                                enabled = true,
                            ),
                            manualDocument = targetDocument ?: spec.manualDocument,
                        ),
                    )
                    if (currentState != null && targetDocument != null) {
                        guidedPass = advanceGuidedTargetPass(targetDocument, currentState)
                    }
                }
            },
        )
    }
    // Structure editing is intentionally a secondary route. Keeping even its collapsed header
    // permanently below the queue made the default surface read like the inspector it replaced.
    if (showAdvancedStructure) {
        ManualDocumentAuxEditors(
            spec, lifelineIds, manualInteractionIdsForSelectedMessages(spec.manualDocument, selectedMessageIds), workspaceKey, onSpec,
            onClearAll = onClearAllManual,
        )
    } else {
        AppButton(
            "More…",
            { showAdvancedStructure = true },
            variant = ButtonVariant.Ghost,
            modifier = Modifier.padding(horizontal = 12.dp),
        )
    }

    if (showRegenerateDialog) {
        Dialog(
            onDismissRequest = { if (!seedBusy) showRegenerateDialog = false },
            // usePlatformDefaultWidth defaults to true, which silently clamps this dialog to the
            // ~580dp ported "preferred dialog width" no matter what width its own content requests
            // (see CLAUDE.md's Dialog-width gotcha) — disabled so the review row list gets real width.
            properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnClickOutside = !seedBusy),
        ) {
            RegenerateDialogContent(
                rangeContent = rangeContent,
                seedConfiguration = seedConfiguration,
                onSeedConfigurationChange = { seedConfiguration = it },
                confirmApply = confirmApply,
                onConfirmApplyChange = { confirmApply = it },
                onApplySeed = onApplySeed,
                onRevertSeed = onRevertSeed,
                canRevertSeed = canRevertSeed,
                seedNeedsConfirmation = seedNeedsConfirmation,
                seedBusy = seedBusy,
                seedStatus = seedStatus,
                manualSeedReview = manualSeedReview,
                onUpdateSeedReview = onUpdateSeedReview,
                onAcceptSeedReview = {
                    onAcceptSeedReview()
                    showRegenerateDialog = false
                },
                onCancelSeedReview = onCancelSeedReview,
                onClose = { showRegenerateDialog = false },
            )
        }
    }
}

/** Stage 3: the dialog opened by the message queue footer's "Regenerate…" button. Hosts exactly
 *  what used to render inline at the top of [DiagramAuthoringSection] — the range/seed-scope
 *  controls and, once a build completes, the regeneration review — with identical callback wiring. */
@Composable
private fun RegenerateDialogContent(
    rangeContent: @Composable () -> Unit,
    seedConfiguration: ManualDiagramSeedConfiguration,
    onSeedConfigurationChange: (ManualDiagramSeedConfiguration) -> Unit,
    confirmApply: Boolean,
    onConfirmApplyChange: (Boolean) -> Unit,
    onApplySeed: (ManualDiagramSeedConfiguration, Boolean) -> Unit,
    onRevertSeed: () -> Unit,
    canRevertSeed: Boolean,
    seedNeedsConfirmation: Boolean,
    seedBusy: Boolean,
    seedStatus: String?,
    manualSeedReview: ManualRegenerationReview?,
    onUpdateSeedReview: (ManualRegenerationReview) -> Unit,
    onAcceptSeedReview: () -> Unit,
    onCancelSeedReview: () -> Unit,
    onClose: () -> Unit,
) {
    val tc = tc()
    Column(
        Modifier.width(560.dp).background(tc.p, CORNER_MD).border(1.dp, tc.br, CORNER_MD)
            .padding(16.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            AppText(
                "Regenerate from source", color = tc.tx, fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f),
            )
            AppButton("Close", onClose, variant = ButtonVariant.Ghost, enabled = !seedBusy)
        }
        AppText(
            "Use the selected log rows to build an initial set of interactions. You can edit the " +
                "result afterward; a later build replaces it only when you apply it.",
            color = tc.td, fontSize = 10.sp, maxLines = 3,
        )
        // The range selects source rows for the next build; it never reorders or otherwise changes
        // existing interactions.
        rangeContent()
        SectionHeader("Build from source", trailing = {
            if (seedBusy) AppText("building", color = tc.td, fontSize = 9.sp)
        })
        CheckRow(
            checked = seedConfiguration.reconstructSourceTrace,
            onToggle = { onSeedConfigurationChange(seedConfiguration.copy(reconstructSourceTrace = !seedConfiguration.reconstructSourceTrace)) },
        ) { AppText("Use verified source trace", fontSize = 10.sp) }
        CheckRow(
            checked = seedConfiguration.inferThreadHandoffs,
            onToggle = { onSeedConfigurationChange(seedConfiguration.copy(inferThreadHandoffs = !seedConfiguration.inferThreadHandoffs)) },
        ) { AppText("Include same-thread handoffs (PID + TID)", fontSize = 10.sp) }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            AppButton(
                "Review regeneration",
                {
                    if (seedNeedsConfirmation) onConfirmApplyChange(true) else onApplySeed(seedConfiguration, false)
                },
                variant = ButtonVariant.Primary,
                enabled = !seedBusy,
            )
            AppButton("Reset", onRevertSeed, variant = ButtonVariant.Ghost, enabled = canRevertSeed && !seedBusy)
        }
        if (confirmApply) {
            AppText(
                "Build a review first; edited and manually created messages remain protected.",
                color = tc.td, fontSize = 9.sp, maxLines = 2,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                AppButton("Cancel", { onConfirmApplyChange(false) }, variant = ButtonVariant.Ghost)
                AppButton("Build review", {
                    onConfirmApplyChange(false)
                    onApplySeed(seedConfiguration, true)
                }, variant = ButtonVariant.Secondary, isDanger = true, enabled = !seedBusy)
            }
        }
        if (seedBusy || seedStatus != null) {
            AppText(seedStatus ?: "Building…", color = tc.td, fontSize = 9.sp, maxLines = 2)
        }
        manualSeedReview?.let { review ->
            RegenerationReviewSection(
                review = review,
                onUpdateSeedReview = onUpdateSeedReview,
                onAcceptSeedReview = onAcceptSeedReview,
                onCancelSeedReview = onCancelSeedReview,
            )
        }
    }
}

/** Stage 3 task 6: the counts-only summary is replaced with a bounded, per-row list — one entry
 *  per [ManualRegenerationReviewRow], each with the action pair appropriate to its
 *  [ManualRegenerationChangeKind] (mockup §08). Every toggle writes through [onUpdateSeedReview]
 *  immediately so the coordinator's own [ManualRegenerationReview] (which [onAcceptSeedReview]
 *  reads with no arguments of its own) always reflects the decisions currently shown here. */
@Composable
private fun RegenerationReviewSection(
    review: ManualRegenerationReview,
    onUpdateSeedReview: (ManualRegenerationReview) -> Unit,
    onAcceptSeedReview: () -> Unit,
    onCancelSeedReview: () -> Unit,
) {
    val tc = tc()
    val changeCount = remember(review) { regenerationChangeCount(review) }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(5.dp)) {
        if (review.rows.isEmpty()) {
            AppText(
                "No changes to review. The regenerated interactions match the current source scope, so nothing will be replaced.",
                color = tc.td,
                fontSize = 10.sp,
                maxLines = 3,
            )
            AppButton("Close review", onCancelSeedReview, variant = ButtonVariant.Ghost)
            return@Column
        }
        AppText(
            "Review regeneration · " + review.newCount + " new · " + review.changedAutoCount +
                " changed auto · " + review.noLongerInSourceCount + " no longer in source · " +
                review.editedKeptCount + " edits kept",
            color = tc.ac,
            fontSize = 9.sp,
            maxLines = 3,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            AppButton("Accept all", { onUpdateSeedReview(acceptAllRegenerationRows(review)) }, variant = ButtonVariant.Ghost)
            AppButton("Reject all", { onUpdateSeedReview(rejectAllRegenerationRows(review)) }, variant = ButtonVariant.Ghost)
        }
        BoundedScrollBoxDp(maxHeightDp = 9 * 26) {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                review.rows.forEachIndexed { index, row ->
                    key(index) {
                        RegenerationReviewRowView(
                            row = row,
                            onDecision = { decision -> onUpdateSeedReview(withRowDecision(review, index, decision)) },
                        )
                    }
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            AppButton("Apply $changeCount changes", onAcceptSeedReview, variant = ButtonVariant.Primary, enabled = changeCount > 0)
            AppButton("Cancel review", onCancelSeedReview, variant = ButtonVariant.Ghost)
        }
    }
}

/** Counts rows whose current decision actually mutates the document when applied — an
 *  [ManualRegenerationChangeKind.EDITED_KEPT] row never does (always kept regardless of
 *  decision), a [ManualRegenerationChangeKind.NO_LONGER_IN_SOURCE] row does only on
 *  [ManualRegenerationRowDecision.REJECT] (removed), and a NEW/CHANGED_AUTO row does only on
 *  [ManualRegenerationRowDecision.ACCEPT] — mirrors [applyReviewedManualRegeneration]'s own
 *  per-kind branching exactly, see that function's doc. */
private fun regenerationChangeCount(review: ManualRegenerationReview): Int = review.rows.count { row ->
    when (row.kind) {
        ManualRegenerationChangeKind.NEW, ManualRegenerationChangeKind.CHANGED_AUTO ->
            row.decision == ManualRegenerationRowDecision.ACCEPT
        ManualRegenerationChangeKind.NO_LONGER_IN_SOURCE ->
            row.decision == ManualRegenerationRowDecision.REJECT
        ManualRegenerationChangeKind.EDITED_KEPT -> false
    }
}

@Composable
private fun RegenerationReviewRowView(
    row: ManualRegenerationReviewRow,
    onDecision: (ManualRegenerationRowDecision) -> Unit,
) {
    val tc = tc()
    val label = when (row.kind) {
        ManualRegenerationChangeKind.NEW -> row.candidate?.let(::manualMessageTemplate) ?: "(untitled message)"
        ManualRegenerationChangeKind.CHANGED_AUTO -> row.candidate?.let(::manualMessageTemplate)
            ?: row.existing?.let(::manualMessageTemplate).orEmpty()
        ManualRegenerationChangeKind.NO_LONGER_IN_SOURCE -> row.existing?.let(::manualMessageTemplate).orEmpty()
        ManualRegenerationChangeKind.EDITED_KEPT -> row.existing?.let(::manualMessageTemplate).orEmpty()
    }
    Row(
        Modifier.fillMaxWidth().padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        AppText(label, fontSize = 10.sp, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
        when (row.kind) {
            ManualRegenerationChangeKind.NEW -> RegenerationDecisionToggle(
                firstLabel = "Skip", firstDecision = ManualRegenerationRowDecision.REJECT,
                secondLabel = "Add", secondDecision = ManualRegenerationRowDecision.ACCEPT,
                decision = row.decision, onDecision = onDecision,
            )
            ManualRegenerationChangeKind.CHANGED_AUTO -> RegenerationDecisionToggle(
                firstLabel = "Keep mine", firstDecision = ManualRegenerationRowDecision.REJECT,
                secondLabel = "Accept", secondDecision = ManualRegenerationRowDecision.ACCEPT,
                decision = row.decision, onDecision = onDecision,
            )
            ManualRegenerationChangeKind.NO_LONGER_IN_SOURCE -> RegenerationDecisionToggle(
                firstLabel = "Keep", firstDecision = ManualRegenerationRowDecision.ACCEPT,
                secondLabel = "Remove", secondDecision = ManualRegenerationRowDecision.REJECT,
                decision = row.decision, onDecision = onDecision,
            )
            ManualRegenerationChangeKind.EDITED_KEPT -> AppText("locked · kept", color = tc.td, fontSize = 9.sp)
        }
    }
    if (row.matchAmbiguous) {
        AppText(
            row.ambiguityReason ?: "Ambiguous evidence-free match; shown as distinct source changes.",
            color = DANGER_RED,
            fontSize = 9.sp,
            maxLines = 2,
        )
    }
}

@Composable
private fun RegenerationDecisionToggle(
    firstLabel: String,
    firstDecision: ManualRegenerationRowDecision,
    secondLabel: String,
    secondDecision: ManualRegenerationRowDecision,
    decision: ManualRegenerationRowDecision,
    onDecision: (ManualRegenerationRowDecision) -> Unit,
) {
    SegmentedControl(
        listOf(firstLabel, secondLabel),
        setOf(if (decision == secondDecision) 1 else 0),
        onToggle = { index -> onDecision(if (index == 0) firstDecision else secondDecision) },
    )
}

/**
 * Compact queue surface. Rows are selectable and expandable, but their order is always the
 * durable authoring order; there is intentionally no pointer-based reorder interaction here.
 */
@Composable
// `focusedManualInteractionId` is plumbed from the caller's real focus state but not yet read in
// this body — see DiagramAuthoringSection's own note above.
@Suppress("UnusedParameter")
private fun ManualMessageQueueEditor(
    spec: SeqDiagramSpec,
    lifelineIds: List<String>,
    entries: List<LogEntry>,
    anchorEntryIds: Set<Int>,
    workspaceKey: String,
    selectedMessageIds: Set<String>,
    selectionAnchorMessageId: String?,
    onSelectionChanged: (Set<String>, String?) -> Unit,
    onSpec: (SeqDiagramSpec) -> Unit,
    onFixTargets: () -> Unit,
    onNavigateEvidence: (Int) -> Unit,
    canOpenSourceEvidence: (Int) -> Boolean,
    onOpenSourceEvidence: (Int) -> Unit,
    focusedManualInteractionId: String?,
    onFocusManualInteraction: (String?) -> Unit,
    hoveredManualInteractionId: String?,
    onHoverManualInteraction: (String?) -> Unit,
    onOpenRegenerateDialog: () -> Unit,
) {
    val document = spec.manualDocument
    var addDraftOpen by remember(workspaceKey) { mutableStateOf(false) }
    var draftMatchText by remember(workspaceKey) { mutableStateOf("") }
    var draftLabel by remember(workspaceKey) { mutableStateOf("") }
    var draftEvidenceId by remember(workspaceKey) { mutableStateOf<Int?>(null) }
    var draftFrom by remember(workspaceKey, lifelineIds) { mutableStateOf(lifelineIds.firstOrNull().orEmpty()) }
    var draftTo by remember(workspaceKey) { mutableStateOf<String?>(null) }

    fun openAddDraft() {
        draftMatchText = ""
        draftLabel = ""
        draftEvidenceId = null
        draftFrom = lifelineIds.firstOrNull().orEmpty()
        draftTo = null
        addDraftOpen = true
    }

    fun commitAddDraft() {
        val evidenceEntry = entries.firstOrNull { it.id == draftEvidenceId } ?: return
        val matchText = draftMatchText.trim()
        if (matchText.isEmpty() || matchManualMessage(ManualMessageMatch(textPattern = matchText), evidenceEntry.msg, evidenceEntry.tag) == null) return
        val normalized = normalizeManualDocument(document)
        val id = "manual-message:draft:" + System.nanoTime()
        val interactionId = "$id:occurrence"
        val interaction = ManualDiagramInteraction(
            id = interactionId,
            sourceEntryIds = setOf(evidenceEntry.id),
            fromParticipantId = draftFrom,
            toParticipantId = draftTo,
            operation = draftLabel.trim().ifEmpty { matchText },
            label = draftLabel.trim().ifEmpty { null },
            kind = if (draftTo == null) MessageKind.CALL else if (draftFrom == draftTo) MessageKind.SELF else MessageKind.CALL,
            order = nextManualOrder(normalized),
            authoring = ManualInteractionAuthoring.EDITED,
            evidence = listOf(ManualDiagramEvidence(evidenceEntry.id, evidenceEntry.ts, evidenceEntry.level)),
            matchText = evidenceEntry.msg,
        )
        val definition = ManualDiagramMessageDefinition(
            id = id,
            occurrenceIds = listOf(interactionId),
            match = ManualMessageMatch(textPattern = matchText),
            fromParticipantId = draftFrom,
            toParticipantId = draftTo,
            labelTemplate = draftLabel.trim().ifEmpty { matchText },
            kind = interaction.kind,
            state = if (draftTo == null) ManualMessageStateKind.NEEDS_TARGET else ManualMessageStateKind.EDITED,
            authoring = ManualInteractionAuthoring.EDITED,
        )
        onSpec(spec.copy(manualDocument = normalized.copy(
            interactions = normalized.interactions + interaction,
            messages = normalized.messages + definition,
        )))
        addDraftOpen = false
    }
    var expandedRowId by remember(workspaceKey) { mutableStateOf<String?>(null) }
    var filter by remember(workspaceKey) { mutableStateOf(ManualMessageFilter.ALL) }
    var sort by remember(workspaceKey) { mutableStateOf(ManualMessageSort.LOG_ORDER) }
    var query by remember(workspaceKey) { mutableStateOf("") }
    val queue = buildManualMessageQueue(document, filter = filter, query = query, sort = sort)
    val totalRowCount = remember(document) { groupManualMessageQueueRows(document).size }
    LaunchedEffect(focusedManualInteractionId, queue.rows) {
        focusedManualInteractionId?.let { focusedId ->
            queue.rows.firstOrNull { row -> row.id == focusedId || focusedId in row.interactionIds }
                ?.let { expandedRowId = it.id }
        }
    }

    // Stage 4 keyboard map: a workspace-local cursor over the currently visible rows, plus the
    // focus plumbing needed for L (jump to evidence / focus search) and M/G (focus the bulk
    // action bar's merge/fragment fields without auto-applying a placeholder value).
    var focusedRowIndex by remember(workspaceKey) { mutableStateOf<Int?>(null) }
    val safeFocusedIndex = focusedRowIndex?.takeIf { it in queue.rows.indices }
    var queueTextFieldFocused by remember(workspaceKey) { mutableStateOf(false) }
    val queueFocusRequester = remember(workspaceKey) { FocusRequester() }
    val searchFieldFocusRequester = remember(workspaceKey) { FocusRequester() }
    val mergeFieldFocusRequester = remember(workspaceKey) { FocusRequester() }
    val fragmentFieldFocusRequester = remember(workspaceKey) { FocusRequester() }
    LaunchedEffect(workspaceKey) { runCatching { queueFocusRequester.requestFocus() } }

    // Stage 5 task 4: hoisted so a canvas click's resulting focusedManualInteractionId can scroll
    // its row into view, in addition to the row list's own default internal scrolling. Row
    // positions are recorded (in the same scrollable content's coordinate space, i.e. raw px, see
    // onGloballyPositioned below) keyed by row id rather than list index, since filter/sort/rebuild
    // can reorder the visible queue.rows list under an unchanged interaction identity.
    val rowListScrollState = remember(workspaceKey) { ScrollState(0) }
    val rowPositions = remember(workspaceKey) { mutableStateMapOf<String, Float>() }
    val scrollScope = rememberCoroutineScope()
    // Read the row position while composing so a canvas click that arrives before the row has
    // laid out gets another chance once onGloballyPositioned publishes it. Reading the map only
    // inside LaunchedEffect would not make that late layout publication a restart key.
    val focusedRow = focusedManualInteractionId?.let { id ->
        queue.rows.firstOrNull { it.id == id || id in it.interactionIds }
    }
    LaunchedEffect(focusedManualInteractionId, queue.rows) {
        val focusedId = focusedManualInteractionId ?: return@LaunchedEffect
        if (queue.rows.none { it.id == focusedId || focusedId in it.interactionIds }) {
            // A canvas hit must reveal its row even when the user was browsing Needs target,
            // Hidden, or a text-filtered subset. The durable document is unchanged; only this
            // workspace-local navigation view returns to All so the focused row can be selected.
            filter = ManualMessageFilter.ALL
            query = ""
        }
    }
    val focusedRowId = focusedRow?.id
    val focusedRowOffset = focusedRowId?.let { rowPositions[it] }
    val focusedRowViewport = rowListScrollState.viewportSize
    LaunchedEffect(focusedRowId, focusedRowOffset, focusedRowViewport) {
        val offset = focusedRowOffset ?: return@LaunchedEffect
        val viewportHeight = rowListScrollState.viewportSize.toFloat()
        val current = rowListScrollState.value.toFloat()
        if (offset < current || offset > current + viewportHeight) {
            scrollScope.launch { rowListScrollState.animateScrollTo(offset.roundToInt().coerceAtLeast(0)) }
        }
    }

    // "The focused row, or the current selection when one exists" — shared by H (hide/show) and
    // the bare 1-9 (set target) bindings; Shift+1-9 (set source) additionally requires the
    // selection to be unambiguous (see below) rather than accepting it outright.
    fun selectedInteractionIds(): Set<String> = manualInteractionIdsForSelectedMessages(document, selectedMessageIds)

    fun focusedOrSelectedInteractionIds(): Set<String> = selectedInteractionIds().ifEmpty {
        safeFocusedIndex?.let { queue.rows.getOrNull(it)?.interactionIds?.toSet() }.orEmpty()
    }

    fun applyToInteractionIds(ids: Set<String>, transform: (ManualDiagramInteraction) -> ManualDiagramInteraction) {
        if (ids.isEmpty()) return
        onSpec(
            spec.copy(
                manualDocument = document.copy(
                    interactions = document.interactions.map { current -> if (current.id in ids) transform(current) else current },
                ),
            ),
        )
    }

    fun handleQueueKeyEvent(event: androidx.compose.ui.input.key.KeyEvent): Boolean {
        if (event.type != KeyEventType.KeyDown || queueTextFieldFocused) return false
        return when {
            event.key == Key.J -> {
                if (queue.rows.isNotEmpty()) focusedRowIndex = ((safeFocusedIndex ?: -1) + 1).coerceAtMost(queue.rows.lastIndex)
                true
            }
            event.key == Key.K -> {
                if (queue.rows.isNotEmpty()) focusedRowIndex = ((safeFocusedIndex ?: queue.rows.size) - 1).coerceAtLeast(0)
                true
            }
            event.isShiftPressed && event.key in MANUAL_DIGIT_KEYS -> {
                // Only when a single row/selection is unambiguous: no selection falls back to the
                // focused row (a single row), and a selection spanning more than one row bucket is
                // rejected rather than guessing which row's source the user meant.
                val selectedRowCount = queue.rows.count { row -> row.id in selectedMessageIds }
                val ids = when {
                    selectedMessageIds.isEmpty() -> safeFocusedIndex?.let { queue.rows.getOrNull(it)?.interactionIds?.toSet() }
                    selectedRowCount == 1 -> selectedInteractionIds()
                    else -> null
                }
                val lifeline = lifelineIds.getOrNull(MANUAL_DIGIT_KEYS.indexOf(event.key))
                if (ids != null && lifeline != null) {
                    applyToInteractionIds(ids) {
                        it.copy(
                            fromParticipantId = lifeline,
                            kind = manualKindAfterEndpointChange(it.kind, lifeline, it.toParticipantId),
                            authoring = ManualInteractionAuthoring.EDITED,
                        )
                    }
                }
                true
            }
            event.key in MANUAL_DIGIT_KEYS -> {
                val lifeline = lifelineIds.getOrNull(MANUAL_DIGIT_KEYS.indexOf(event.key))
                if (lifeline != null) {
                    applyToInteractionIds(focusedOrSelectedInteractionIds()) {
                        it.copy(
                            toParticipantId = lifeline,
                            kind = manualKindAfterEndpointChange(it.kind, it.fromParticipantId, lifeline),
                            authoring = ManualInteractionAuthoring.EDITED,
                        )
                    }
                }
                true
            }
            event.key == Key.F -> { onFixTargets(); true }
            event.key == Key.E -> {
                safeFocusedIndex?.let { queue.rows.getOrNull(it)?.id }?.let { id ->
                    expandedRowId = if (expandedRowId == id) null else id
                }
                true
            }
            event.key == Key.H -> {
                val ids = focusedOrSelectedInteractionIds()
                if (ids.isNotEmpty()) {
                    val allHidden = ids.all { id -> document.interactions.firstOrNull { it.id == id }?.enabled == false }
                    applyToInteractionIds(ids) { it.copy(enabled = allHidden, authoring = ManualInteractionAuthoring.EDITED) }
                }
                true
            }
            // Neither auto-applies a placeholder key/id — both only move keyboard focus into the
            // bulk action bar's own (already-visible-once-selected) merge-group-key/fragment-id field.
            event.key == Key.M -> {
                if (selectedMessageIds.size >= 2) runCatching { mergeFieldFocusRequester.requestFocus() }
                true
            }
            event.key == Key.G -> {
                if (selectedMessageIds.size >= 2) runCatching { fragmentFieldFocusRequester.requestFocus() }
                true
            }
            event.key == Key.L -> {
                val evidenceId = safeFocusedIndex?.let { queue.rows.getOrNull(it) }?.sourceEntryIds?.minOrNull()
                if (evidenceId != null) onNavigateEvidence(evidenceId) else runCatching { searchFieldFocusRequester.requestFocus() }
                true
            }
            event.key == Key.Escape -> {
                // Exiting an active guided pass is handled by GuidedTargetPassCard's own Esc
                // binding — the two composables are mutually exclusive (see DiagramAuthoringSection),
                // so there is nothing further for this handler to do in that case.
                if (selectedMessageIds.isNotEmpty()) {
                    onSelectionChanged(emptySet(), null)
                    true
                } else {
                    false
                }
            }
            else -> false
        }
    }

    Column(
        Modifier.fillMaxWidth()
            .focusRequester(queueFocusRequester)
            .focusable()
            // Bubble phase, not preview: a focused text field inside this panel (search, an
            // operation/parameter field, …) consumes its own printable keys before they would ever
            // reach here, so normal typing elsewhere in the panel is never swallowed by these
            // shortcuts — the explicit `queueTextFieldFocused` guard above additionally covers the
            // search field by name, per the plan's own focus-tracking instruction.
            .onKeyEvent { handleQueueKeyEvent(it) },
        // Matches the ancestor Column's own spacing (SeqDiagramWorkspace.kt's inspector column) so
        // wrapping this editor's body in one focusable node doesn't change the visible layout.
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        SectionHeader(
            "Messages",
            trailing = {
                val suffix = if (queue.needsTargetCount > 0) {
                    " · " + queue.needsTargetCount + " needs target"
                } else {
                    ""
                }
                AppText(queue.rows.size.toString() + suffix, color = tc().td, fontSize = 9.sp)
                AppButton("Add +", ::openAddDraft, variant = ButtonVariant.Ghost, enabled = lifelineIds.isNotEmpty())
            },
        )
        AppText(
            "Select rows for safe bulk actions. Each row keeps its source evidence and shows the exact From → To endpoint.",
            color = tc().td,
            fontSize = 9.sp,
            maxLines = 2,
            modifier = Modifier.padding(horizontal = 12.dp),
        )
        if (queue.needsTargetCount > 0) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                AppText(
                    queue.needsTargetCount.toString() + " messages need a target",
                    color = DANGER_RED,
                    fontSize = 10.sp,
                    modifier = Modifier.weight(1f),
                )
                AppButton("Fix these", onFixTargets, variant = ButtonVariant.Secondary)
            }
        }
        InlineField(
            query,
            { query = it },
            "Search messages, lifelines, or evidence row",
            Modifier.fillMaxWidth().padding(horizontal = 12.dp)
                .focusRequester(searchFieldFocusRequester)
                .onFocusChanged { queueTextFieldFocused = it.isFocused },
            fontSize = 10.sp,
        )
        // Stage 3: a chip per ManualMessageFilter (active chip styled Primary, same convention as
        // QueueEndpointChoice's selected-lifeline chip below) replaces the old single button that
        // cycled through the enum on every click — every filter is now a one-click target instead
        // of an opaque "Filter: all" label the user had to click through blind.
        Row(
            Modifier.padding(horizontal = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(5.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ManualMessageFilter.entries.forEach { value ->
                AppButton(
                    value.name.lowercase().replace('_', ' '),
                    { filter = value },
                    variant = if (filter == value) ButtonVariant.Primary else ButtonVariant.Ghost,
                )
            }
        }
        Row(
            Modifier.padding(horizontal = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AppText("Sort", color = tc().td, fontSize = 9.sp)
            ManualMessageSortChoice(sort) { sort = it }
            if (selectedMessageIds.isNotEmpty()) {
                AppText(selectedMessageIds.size.toString() + " selected", color = tc().ac, fontSize = 9.sp)
            }
        }
        if (lifelineIds.isEmpty()) {
            AppText(
                "Add at least one lifeline before creating interactions.",
                color = tc().td,
                fontSize = 10.sp,
                modifier = Modifier.padding(horizontal = 12.dp),
            )
        } else {
            // Stage 5 task 4: the hoisted rowListScrollState (declared above) replaces the private
            // default BoundedScrollBoxDp would otherwise create, so a canvas click's resulting
            // focusedManualInteractionId (the LaunchedEffect above) can drive this same scroll
            // position; every other BoundedScrollBoxDp call site is unaffected since the parameter
            // still defaults to a private rememberScrollState() there.
            BoundedScrollBoxDp(maxHeightDp = 9 * SEQUENCE_EDITOR_ROW_HEIGHT.value.toInt(), scrollState = rowListScrollState) {
                Column(Modifier.fillMaxWidth()) {
                    queue.rows.forEachIndexed { index, row ->
                        key(row.id) {
                            // Stage 5 task 4: records this row's position in the scrollable content's
                            // OWN coordinate frame (positionInParent against the immediate Column
                            // parent, which the verticalScroll offset is never applied to — that
                            // offset is applied one level up, when THIS Column is placed within
                            // BoundedScrollBoxDp's Box) — the same frame rowListScrollState.value
                            // addresses, so the LaunchedEffect above can compare them directly.
                            Box(
                                Modifier.onGloballyPositioned { coordinates ->
                                    rowPositions[row.id] = coordinates.positionInParent().y
                                },
                            ) {
                                ManualMessageQueueRowView(
                                    row = row,
                                    expanded = expandedRowId == row.id,
                                    selected = row.id in selectedMessageIds,
                                    focused = index == safeFocusedIndex,
                                    // Stage 5 task 3: the queue row and canvas both use the durable
                                    // bucket id. Keep the interaction-id fallback for canvas hits from
                                    // older/in-flight previews while the shared group id is settling.
                                    hovered = hoveredManualInteractionId != null &&
                                        (row.id == hoveredManualInteractionId || hoveredManualInteractionId in row.interactionIds),
                                    onToggleExpanded = {
                                        expandedRowId = if (expandedRowId == row.id) null else row.id
                                    },
                                    onToggleSelected = { additive, range ->
                                        onFocusManualInteraction(row.id)
                                        val (next, anchor) = selectManualQueueMessageIds(
                                            visibleMessageIds = queue.rows.map { it.id },
                                            selectedMessageIds = selectedMessageIds,
                                            anchorMessageId = selectionAnchorMessageId,
                                            clickedMessageId = row.id,
                                            additive = additive,
                                            range = range,
                                        )
                                        onSelectionChanged(next, anchor)
                                    },
                                    lifelineIds = lifelineIds,
                                    entries = entries,
                                    onNavigateEvidence = onNavigateEvidence,
                                    canOpenSourceEvidence = canOpenSourceEvidence,
                                    onOpenSourceEvidence = onOpenSourceEvidence,
                                    onHoverManualInteraction = onHoverManualInteraction,
                                    repeatPresentation = manualMessageRepeatPresentation(row.message, document.repeatPresentation),
                                    onChangeRepeatPresentation = { presentation ->
                                        val nextDocument = if (row.message == null) {
                                            document.copy(repeatPresentation = presentation)
                                        } else {
                                            document.copy(
                                                messages = document.messages.map { definition ->
                                                    if (definition.id == row.id) definition.copy(
                                                        repeatPolicy = manualMessageRepeatPolicy(presentation),
                                                        state = ManualMessageStateKind.EDITED,
                                                        authoring = ManualInteractionAuthoring.EDITED,
                                                    ) else definition
                                                },
                                            )
                                        }
                                        onSpec(spec.copy(manualDocument = nextDocument))
                                    },
                                onChange = { changed ->
                                    val ids = row.interactionIds.toSet()
                                    val nextDocument = document.copy(
                                        interactions = document.interactions.map { current ->
                                            if (current.id !in ids) current else current.copy(
                                                fromParticipantId = changed.fromParticipantId,
                                                toParticipantId = changed.toParticipantId,
                                                operation = changed.operation,
                                                parameters = changed.parameters,
                                                result = changed.result,
                                                label = changed.label,
                                                kind = changed.kind,
                                                visibility = changed.visibility,
                                                enabled = changed.enabled,
                                                authoring = ManualInteractionAuthoring.EDITED,
                                            )
                                        },
                                        messages = document.messages.map { definition ->
                                            if (definition.id != row.id) definition else definition.copy(
                                                fromParticipantId = changed.fromParticipantId,
                                                toParticipantId = changed.toParticipantId,
                                                labelTemplate = manualMessageTemplate(changed),
                                                kind = changed.kind,
                                                visibility = if (changed.enabled) ManualMessageVisibility.VISIBLE else ManualMessageVisibility.HIDDEN,
                                                state = if (changed.toParticipantId == null) ManualMessageStateKind.NEEDS_TARGET else ManualMessageStateKind.EDITED,
                                                authoring = ManualInteractionAuthoring.EDITED,
                                            )
                                        },
                                    )
                                    onSpec(spec.copy(manualDocument = nextDocument))
                                },
                                onHide = {
                                    val ids = row.interactionIds.toSet()
                                    onSpec(spec.copy(manualDocument = document.copy(
                                        interactions = document.interactions.map { current ->
                                            if (current.id in ids) current.copy(enabled = false, authoring = ManualInteractionAuthoring.EDITED) else current
                                        },
                                        messages = document.messages.map { definition ->
                                            if (definition.id == row.id) definition.copy(
                                                visibility = ManualMessageVisibility.HIDDEN,
                                                state = ManualMessageStateKind.HIDDEN,
                                                authoring = ManualInteractionAuthoring.EDITED,
                                            ) else definition
                                        },
                                    )))
                                },
                                onUnlock = {
                                    val ids = row.interactionIds.toSet()
                                    onSpec(spec.copy(manualDocument = document.copy(
                                        interactions = document.interactions.map { current ->
                                            if (current.id in ids) unlockManualInteraction(current) else current
                                        },
                                        messages = document.messages.map { definition ->
                                            if (definition.id == row.id) definition.copy(
                                                state = ManualMessageStateKind.AUTO,
                                                authoring = ManualInteractionAuthoring.AUTO,
                                            ) else definition
                                        },
                                    )))
                                },
                            )
                            }
                        }
                    }
                }
            }
        }
        if (selectedMessageIds.isNotEmpty()) {
            ManualMessageBulkActionBar(
                spec = spec,
                document = document,
                selectedMessageIds = selectedMessageIds,
                lifelineIds = lifelineIds,
                onSpec = onSpec,
                onClear = { onSelectionChanged(emptySet(), null) },
                mergeFieldFocusRequester = mergeFieldFocusRequester,
                fragmentFieldFocusRequester = fragmentFieldFocusRequester,
            )
        } else {
            // The normal footer deliberately yields to the selection tray above: switching from
            // browse mode into a multi-message operation should leave one unambiguous action
            // surface at the bottom of the queue, rather than two competing rows of controls.
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                AppText(
                    queue.rows.size.toString() + " shown · " + totalRowCount + " total" +
                        if (queue.needsTargetCount > 0) " · " + queue.needsTargetCount + " needs target" else "",
                    color = tc().td,
                    fontSize = 9.sp,
                    modifier = Modifier.weight(1f),
                )
                AppButton("Regenerate…", onOpenRegenerateDialog, variant = ButtonVariant.Secondary)
            }
        }
    }

    if (addDraftOpen) {
        val selectedEvidence = entries.firstOrNull { it.id == draftEvidenceId }
        val matchValid = selectedEvidence != null && draftMatchText.trim().isNotEmpty() &&
            matchManualMessage(ManualMessageMatch(textPattern = draftMatchText.trim()), selectedEvidence.msg, selectedEvidence.tag) != null
        Dialog(
            onDismissRequest = { addDraftOpen = false },
            properties = DialogProperties(usePlatformDefaultWidth = false),
        ) {
            Column(
                Modifier.width(560.dp).background(tc().p, CORNER_MD).border(1.dp, tc().br, CORNER_MD).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                AppText("Add message draft", fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                AppText("Enter a match that exactly proves against one selected evidence row. The label stays separate from matching.", color = tc().td, fontSize = 10.sp, maxLines = 2)
                InlineField(draftMatchText, { draftMatchText = it }, "match text", Modifier.fillMaxWidth(), fontSize = 10.sp)
                InlineField(draftLabel, { draftLabel = it }, "label template (optional)", Modifier.fillMaxWidth(), fontSize = 10.sp)
                AppText("Evidence", color = tc().td, fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
                entries.take(16).forEach { entry ->
                    AppButton(
                        "${entry.id} · ${entry.msg}",
                        { draftEvidenceId = entry.id; if (draftMatchText.isBlank()) draftMatchText = entry.msg },
                        variant = if (entry.id == draftEvidenceId) ButtonVariant.Primary else ButtonVariant.Ghost,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    AppText("From", color = tc().td, fontSize = 10.sp)
                    QueueEndpointChoice(draftFrom, lifelineIds) { draftFrom = it }
                    AppText("To", color = tc().td, fontSize = 10.sp)
                    QueueEndpointChoice(draftTo ?: "Needs target", lifelineIds, allowNeedsTarget = true) {
                        draftTo = it.takeUnless(String::isBlank)
                    }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.End)) {
                    AppButton("Cancel", { addDraftOpen = false }, variant = ButtonVariant.Ghost)
                    AppButton("Add message", ::commitAddDraft, variant = ButtonVariant.Primary, enabled = matchValid)
                }
            }
        }
    }
}

@Composable
private fun ManualMessageSortChoice(value: ManualMessageSort, onSelect: (ManualMessageSort) -> Unit) {
    val values = ManualMessageSort.entries
    SegmentedControl(
        values.map { it.name.lowercase().replace('_', ' ') },
        setOf(values.indexOf(value)),
        onToggle = { index -> onSelect(values[index]) },
    )
}

@Composable
private fun ManualMessageBulkActionBar(
    spec: SeqDiagramSpec,
    document: ManualDiagramDocument,
    selectedMessageIds: Set<String>,
    lifelineIds: List<String>,
    onSpec: (SeqDiagramSpec) -> Unit,
    onClear: () -> Unit,
    // Stage 4's M/G keyboard shortcuts move focus into these fields (never auto-applying a
    // placeholder value) instead of duplicating this bar's own Merge/Group-as-fragment logic.
    mergeFieldFocusRequester: FocusRequester,
    fragmentFieldFocusRequester: FocusRequester,
) {
    var endpointMenu by remember { mutableStateOf<String?>(null) }
    var groupKey by remember { mutableStateOf("message-group") }
    var fragmentId by remember { mutableStateOf("fragment") }
    var fragmentLabel by remember { mutableStateOf("Fragment") }
    var fragmentKind by remember { mutableStateOf(ManualFragmentKind.CUSTOM) }
    var noteText by remember { mutableStateOf("") }
    var detailsAction by remember { mutableStateOf<String?>(null) }
    var actionMessage by remember { mutableStateOf<String?>(null) }
    val selectedInteractionIds = manualInteractionIdsForSelectedMessages(document, selectedMessageIds)
    val selected = document.interactions.filter { it.id in selectedInteractionIds }
    val allHidden = selected.all { !it.enabled }

    fun apply(action: ManualMessageBulkAction) {
        val result = applyManualMessageBulkAction(document, selectedMessageIds, action)
        if (result.applied) {
            actionMessage = null
            onSpec(spec.copy(manualDocument = result.document))
        } else {
            actionMessage = result.reason
        }
    }
    Column(
        Modifier.fillMaxWidth().background(tc().abg, CORNER_SM).padding(horizontal = 12.dp, vertical = 5.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            AppText("Bulk actions", color = tc().ac, fontSize = 10.sp, modifier = Modifier.weight(1f))
            AppButton("Esc", onClear, variant = ButtonVariant.Ghost)
        }
        // The bulk endpoint picker must escape the queue's scrolling viewport just like the
        // card-level endpoint picker.  Rendering choices in-flow was the source of the apparent
        // empty “Set target” list reported from the workspace.
        Box {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                AppButton("Set from", { endpointMenu = if (endpointMenu == "from") null else "from" }, variant = ButtonVariant.Secondary)
                AppButton("Set target", { endpointMenu = if (endpointMenu == "target") null else "target" }, variant = ButtonVariant.Secondary)
                AppButton("Merge", { detailsAction = if (detailsAction == "merge") null else "merge" }, variant = ButtonVariant.Ghost)
                AppButton("Group", { detailsAction = if (detailsAction == "group") null else "group" }, variant = ButtonVariant.Ghost)
                AppButton(
                    if (allHidden) "Show" else "Hide",
                    { apply(if (allHidden) ManualMessageBulkAction.Show else ManualMessageBulkAction.Hide) },
                    variant = ButtonVariant.Ghost,
                )
                AppButton("Note", { detailsAction = if (detailsAction == "note") null else "note" }, variant = ButtonVariant.Ghost)
            }
            if (endpointMenu != null) {
                Popup(
                    alignment = Alignment.BottomStart,
                    offset = IntOffset(0, 4),
                    onDismissRequest = { endpointMenu = null },
                    properties = PopupProperties(focusable = true),
                ) {
                    Column(
                        Modifier.width(210.dp).background(tc().p, CORNER_SM).border(1.dp, tc().br, CORNER_SM).padding(4.dp),
                        verticalArrangement = Arrangement.spacedBy(3.dp),
                    ) {
                        if (endpointMenu == "target") {
                            AppButton(
                                "Needs target",
                                { apply(ManualMessageBulkAction.SetTarget(null)); endpointMenu = null },
                                variant = ButtonVariant.Ghost,
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                        lifelineIds.forEach { id ->
                            AppButton(
                                id,
                                {
                                    apply(
                                        if (endpointMenu == "from") ManualMessageBulkAction.SetSource(id)
                                        else ManualMessageBulkAction.SetTarget(id),
                                    )
                                    endpointMenu = null
                                },
                                variant = ButtonVariant.Ghost,
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    }
                }
            }
        }
        if (detailsAction == "merge") {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                InlineField(
                    groupKey, { groupKey = it }, "merge group key",
                    Modifier.weight(1f).focusRequester(mergeFieldFocusRequester), fontSize = 9.sp,
                )
                AppButton("Merge selected", { apply(ManualMessageBulkAction.Merge(groupKey)); detailsAction = null }, variant = ButtonVariant.Ghost, enabled = groupKey.isNotBlank())
            }
        }
        if (detailsAction == "group") {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                InlineField(
                    fragmentId, { fragmentId = it }, "fragment id",
                    Modifier.weight(1f).focusRequester(fragmentFieldFocusRequester), fontSize = 9.sp,
                )
                InlineField(fragmentLabel, { fragmentLabel = it }, "fragment label", Modifier.weight(1f), fontSize = 9.sp)
            }
            ManualFragmentKindChoice(fragmentKind, { fragmentKind = it })
            AppButton(
                "Group as fragment",
                {
                    apply(ManualMessageBulkAction.GroupAsFragment(com.indagium.diagram.ManualDiagramGroup(fragmentId, fragmentLabel, selectedInteractionIds.toList(), kind = fragmentKind)))
                    detailsAction = null
                },
                variant = ButtonVariant.Ghost,
                enabled = fragmentId.isNotBlank() && fragmentLabel.isNotBlank(),
            )
        }
        actionMessage?.let { message -> AppText(message, color = DANGER_RED, fontSize = 9.sp) }
        if (detailsAction == "note") Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
            InlineField(noteText, { noteText = it }, "note text", Modifier.weight(1f), fontSize = 9.sp)
            AppButton(
                "Add note",
                {
                    val anchor = selected.lastOrNull()?.id ?: return@AppButton
                    val participant = selected.firstOrNull()?.fromParticipantId ?: return@AppButton
                    apply(
                        ManualMessageBulkAction.AddNote(
                            com.indagium.diagram.ManualDiagramNote(
                                id = "note-" + System.nanoTime(),
                                participantId = participant,
                                afterInteractionId = anchor,
                                text = noteText,
                            ),
                        ),
                    )
                    detailsAction = null
                },
                variant = ButtonVariant.Ghost,
                enabled = noteText.isNotBlank() && selected.isNotEmpty(),
            )
        }
    }
}

@Composable
private fun ManualFragmentKindChoice(
    selected: ManualFragmentKind,
    onSelect: (ManualFragmentKind) -> Unit,
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AppText("Fragment type", color = tc().td, fontSize = 9.sp)
        ManualFragmentKind.entries.forEach { kind ->
            AppButton(
                kind.name.lowercase(),
                { onSelect(kind) },
                variant = if (kind == selected) ButtonVariant.Primary else ButtonVariant.Ghost,
            )
        }
    }
}

@Composable
private fun ManualMessageQueueRowView(
    row: ManualMessageQueueRow,
    expanded: Boolean,
    selected: Boolean,
    hovered: Boolean,
    // Stage 4: true while this row is the keyboard cursor's current target (J/K). Purely a
    // presentation hint — never selection, and never written back into any model state.
    focused: Boolean,
    onToggleExpanded: () -> Unit,
    onToggleSelected: (additive: Boolean, range: Boolean) -> Unit,
    lifelineIds: List<String>,
    entries: List<LogEntry>,
    onNavigateEvidence: (Int) -> Unit,
    canOpenSourceEvidence: (Int) -> Boolean,
    onOpenSourceEvidence: (Int) -> Unit,
    onHoverManualInteraction: (String?) -> Unit,
    repeatPresentation: ManualDiagramRepeatPresentation,
    onChangeRepeatPresentation: (ManualDiagramRepeatPresentation) -> Unit,
    onChange: (ManualDiagramInteraction) -> Unit,
    onHide: () -> Unit,
    onUnlock: () -> Unit,
) {
    val representative = row.representative
    // The compact card deliberately has two levels: identity first, then its authored route.
    // Evidence and every other detailed field stay behind the selected-only disclosure so a long
    // log never turns into the old inspector/table surface.
    val showDetails = selected && expanded
    val stateLabel = when (row.state) {
        ManualMessageState.NEEDS_TARGET -> "needs target"
        ManualMessageState.EDITED -> "edited"
        ManualMessageState.HIDDEN -> "hidden"
        ManualMessageState.AUTO -> "auto"
    }
    Column(
        Modifier.fillMaxWidth()
            .background(
                when {
                    selected -> tc().abg
                    hovered -> tc().abg.copy(alpha = 0.55f)
                    else -> Color.Transparent
                },
                CORNER_SM,
            )
            .then(
                when {
                    hovered -> Modifier.border(1.dp, tc().ac.copy(alpha = 0.55f), CORNER_SM)
                    focused -> Modifier.border(1.dp, tc().ac.copy(alpha = 0.5f), CORNER_SM)
                    else -> Modifier
                },
            )
            // Stage 1c bug fix: hovering a row used to call onFocusManualInteraction — the same
            // callback row-click/canvas-click use to drive multi-selection — so merely hovering
            // silently changed the selection and popped the bulk-action bar. Hover now only drives
            // the (separate) row↔canvas hover-highlight sync; onFocusManualInteraction stays
            // reserved for real selection-changing clicks (ManualRoundToggle's onToggleSelected).
            .onPointerEvent(PointerEventType.Enter) {
                // row.id is the shared manual-message bucket identity. It is stable for both
                // grouped rows and ungrouped rows, and is the same identity emitted into canvas
                // hit metadata by the manual builder.
                onHoverManualInteraction(row.id)
            }
            .onPointerEvent(PointerEventType.Exit) {
                onHoverManualInteraction(null)
            }
            .padding(horizontal = 12.dp, vertical = 3.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Row(
            Modifier.fillMaxWidth().height(SEQUENCE_EDITOR_ROW_HEIGHT),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            var additiveSelection by remember(row.id) { mutableStateOf(false) }
            var rangeSelection by remember(row.id) { mutableStateOf(false) }
            Box(
                Modifier.onPointerEvent(PointerEventType.Press) { event ->
                    val modifiers = event.keyboardModifiers
                    additiveSelection = modifiers.isCtrlPressed || modifiers.isMetaPressed
                    rangeSelection = modifiers.isShiftPressed
                },
            ) {
                ManualRoundToggle(
                    tooltip = if (selected) "Deselect message" else "Select message",
                    active = selected,
                    color = tc().td,
                    onClick = {
                        onToggleSelected(additiveSelection, rangeSelection)
                        additiveSelection = false
                        rangeSelection = false
                    },
                )
            }
            AppText(
                manualMessageDisplayTemplate(row),
                fontSize = 10.sp,
                fontWeight = FontWeight.SemiBold,
                // Stage 1c: strikethrough follows `row.hidden` alone, independently of whichever
                // `stateLabel` word ends up shown below (a hidden row that is also edited still
                // shows "edited" text, but stays struck through) — `hidden` remains the single
                // durable/filter source of truth, this only decouples how it's styled.
                textDecoration = if (row.hidden) TextDecoration.LineThrough else null,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (row.occurrenceCount > 1) {
                AppText(row.occurrenceCount.toString() + "×", color = tc().td, fontSize = 9.sp)
            }
        }
        Row(
            Modifier.fillMaxWidth().padding(start = 28.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            QueueEndpointChoice(representative.fromParticipantId, lifelineIds) { selected ->
                onChange(
                    representative.copy(
                        fromParticipantId = selected,
                        kind = manualKindAfterEndpointChange(
                            representative.kind,
                            selected,
                            representative.toParticipantId,
                        ),
                    ),
                )
            }
            AppText("→", color = tc().td, fontSize = 10.sp)
            QueueEndpointChoice(
                representative.toParticipantId ?: "Needs target",
                lifelineIds,
                allowNeedsTarget = true,
            ) { selected ->
                onChange(
                    representative.copy(
                        toParticipantId = selected.takeUnless { it.isBlank() },
                        kind = manualKindAfterEndpointChange(
                            representative.kind,
                            representative.fromParticipantId,
                            selected.takeUnless { it.isBlank() },
                        ),
                    ),
                )
            }
            AppText(stateLabel, color = if (row.state == ManualMessageState.NEEDS_TARGET) DANGER_RED else tc().td, fontSize = 9.sp)
            AppButton(
                if (showDetails) "Close" else "Details",
                onToggleExpanded,
                variant = ButtonVariant.Ghost,
                enabled = selected,
            )
        }
        if (showDetails) {
            AppText(
                if (row.state == ManualMessageState.NEEDS_TARGET) {
                    "Evidence-backed dashed stub · rows " + row.sourceEntryIds.sorted().joinToString(", ")
                } else {
                    "Evidence rows " + row.sourceEntryIds.sorted().joinToString(", ")
                },
                color = tc().td,
                fontSize = 9.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(start = 28.dp),
            )
            val liveEvidenceId = row.sourceEntryIds.sorted().firstOrNull { entryId ->
                entries.any { it.id == entryId }
            }
            if (liveEvidenceId != null) {
                AppButton(
                    "Open evidence",
                    { onNavigateEvidence(liveEvidenceId) },
                    variant = ButtonVariant.Ghost,
                    modifier = Modifier.padding(start = 28.dp),
                )
            } else if (row.sourceEntryIds.isNotEmpty()) {
                AppText(
                    "Evidence retained; source row unavailable",
                    color = tc().td,
                    fontSize = 9.sp,
                    modifier = Modifier.padding(start = 28.dp),
                )
            }
            // Stage 3b: a read-only evidence sub-list, deliberately separate from
            // ManualInteractionCard's editable fields below it — the evidence rows themselves are
            // never editable here (mockup §03's "trust anchor"), only click-navigable.
            ManualEvidenceList(
                row = row,
                entries = entries,
                onNavigateEvidence = onNavigateEvidence,
            )
            ManualSourceProvenance(
                interaction = representative,
                canOpenSourceEvidence = canOpenSourceEvidence,
                onOpenSourceEvidence = onOpenSourceEvidence,
            )
            if (row.occurrenceCount > 1) {
                ManualRepeatPresentationChoice(
                    value = repeatPresentation,
                    onSelect = onChangeRepeatPresentation,
                )
                AppText(
                    "Edits apply to all ${row.occurrenceCount} compatible occurrences in this group.",
                    color = tc().td,
                    fontSize = 9.sp,
                    modifier = Modifier.padding(horizontal = 12.dp),
                )
            }
            if (representative.authoring == ManualInteractionAuthoring.EDITED) {
                AppButton(
                    "Unlock",
                    onUnlock,
                    variant = ButtonVariant.Ghost,
                    modifier = Modifier.padding(start = 28.dp),
                )
            }
            ManualInteractionCard(
                interaction = representative,
                lifelineIds = lifelineIds,
                onChange = onChange,
                onDelete = onHide,
                onDuplicate = {},
                showActions = false,
            )
        }
    }
}

@Composable
private fun ManualRepeatPresentationChoice(
    value: ManualDiagramRepeatPresentation,
    onSelect: (ManualDiagramRepeatPresentation) -> Unit,
) {
    val values = ManualDiagramRepeatPresentation.entries
    Column(Modifier.padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        AppText("Repeat presentation", color = tc().td, fontSize = 9.sp)
        SegmentedControl(
            values.map {
                when (it) {
                    ManualDiagramRepeatPresentation.CONSECUTIVE -> "consecutive"
                    ManualDiagramRepeatPresentation.EVERY_OCCURRENCE -> "every occurrence"
                    ManualDiagramRepeatPresentation.FIRST_AND_LAST -> "first and last"
                }
            },
            setOf(values.indexOf(value)),
            onToggle = { index -> onSelect(values[index]) },
        )
        AppText(
            "Only adjacent compatible evidence is collapsed; interleaved events keep their log order.",
            color = tc().td,
            fontSize = 9.sp,
            maxLines = 2,
        )
    }
}

/** Read-only source-trace facts for a message. Opening source is always explicit. */
@Composable
private fun ManualSourceProvenance(
    interaction: ManualDiagramInteraction,
    canOpenSourceEvidence: (Int) -> Boolean,
    onOpenSourceEvidence: (Int) -> Unit,
) {
    val owner = interaction.sourceOwnerType
    val operation = interaction.sourceMethodId
    val site = interaction.sourceLogSiteId
    if (owner == null && operation == null && site == null) return
    val evidenceId = manualEvidenceEntryIds(interaction).minOrNull()
    Column(Modifier.padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        AppText("Source provenance", color = tc().td, fontSize = 9.sp, fontWeight = FontWeight.SemiBold)
        owner?.let { AppText("Owner: $it", color = tc().td, fontSize = 9.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) }
        operation?.let { AppText("Operation: $it", color = tc().td, fontSize = 9.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) }
        site?.let { AppText("Site: $it", color = tc().td, fontSize = 9.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) }
        AppText("Trace: source-derived provenance", color = tc().td, fontSize = 9.sp)
        val sourceAvailable = evidenceId?.let(canOpenSourceEvidence) == true
        if (evidenceId != null) {
            AppButton(
                "Open source location",
                { onOpenSourceEvidence(evidenceId) },
                variant = ButtonVariant.Ghost,
                enabled = sourceAvailable,
            )
            if (!sourceAvailable) {
                AppText("Source index unavailable", color = tc().td, fontSize = 9.sp)
            }
        }
    }
}

/**
 * Stage 3b: a read-only evidence sub-list for one row's occurrences — line number, timestamp,
 * tag, and message text, each click-navigable via [onNavigateEvidence]. Deliberately separate
 * from [ManualInteractionCard]'s editable fields: per the design mockup, evidence is "the trust
 * anchor — never editable," so nothing here writes back into the manual document.
 */
@Composable
private fun ManualEvidenceList(
    row: ManualMessageQueueRow,
    entries: List<LogEntry>,
    onNavigateEvidence: (Int) -> Unit,
) {
    val tc = tc()
    data class EvidenceDisplay(
        val id: Int,
        val timestamp: String,
        val level: String,
        val entry: LogEntry?,
    )
    val evidenceEntries = remember(row.interactions, entries) {
        val liveEntries = entries.associateBy { it.id }
        val displays = linkedMapOf<Int, EvidenceDisplay>()
        row.interactions
            .sortedWith(compareBy<ManualDiagramInteraction> { it.order }.thenBy { it.id })
            .forEach { interaction ->
                val snapshots = interaction.evidence.associateBy { it.entryId }
                (interaction.sourceEntryIds + snapshots.keys).sorted().forEach { entryId ->
                    val live = liveEntries[entryId]
                    val snapshot = snapshots[entryId]
                    displays.putIfAbsent(
                        entryId,
                        EvidenceDisplay(
                            id = entryId,
                            timestamp = live?.ts ?: snapshot?.timestamp ?: interaction.renderAnchorTs ?: "Unavailable",
                            level = live?.level?.name ?: snapshot?.level?.name ?: interaction.renderAnchorLevel?.name ?: "—",
                            entry = live,
                        ),
                    )
                }
            }
        displays.values.toList()
    }
    if (evidenceEntries.isEmpty()) return
    Column(Modifier.padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        AppText("Evidence (read-only)", color = tc.td, fontSize = 9.sp, fontWeight = FontWeight.SemiBold)
        BoundedScrollBoxDp(maxHeightDp = 5 * 20) {
            Column(Modifier.fillMaxWidth()) {
                evidenceEntries.forEach { entry ->
                    Row(
                        Modifier.fillMaxWidth()
                            .then(
                                if (entry.entry != null) Modifier.clickable { onNavigateEvidence(entry.id) } else Modifier,
                            )
                            .padding(vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        AppText(entry.id.toString(), color = tc.td, fontSize = 9.sp, modifier = Modifier.width(32.dp))
                        AppText(entry.timestamp, color = tc.td, fontSize = 9.sp, modifier = Modifier.width(72.dp), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        AppText(
                            (entry.entry?.level?.name ?: entry.level) + " · " + (entry.entry?.tag ?: "retained"),
                            color = tc.ac, fontSize = 9.sp, modifier = Modifier.width(90.dp),
                            maxLines = 1, overflow = TextOverflow.Ellipsis,
                        )
                        AppText(
                            entry.entry?.msg ?: "Retained evidence — source row unavailable", color = tc.ts, fontSize = 9.sp, maxLines = 1,
                            overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun QueueEndpointChoice(
    selected: String,
    lifelineIds: List<String>,
    allowNeedsTarget: Boolean = false,
    onSelect: (String) -> Unit,
) {
    var expanded by remember(selected) { mutableStateOf(false) }
    var anchorHeightPx by remember { mutableStateOf(0) }
    val menuWidth = 188.dp
    Box(
        Modifier.onSizeChanged { anchorHeightPx = it.height },
        contentAlignment = Alignment.TopEnd,
    ) {
        AppButton(
            "$selected ▾",
            { expanded = !expanded },
            variant = ButtonVariant.Secondary,
        )
        if (expanded) {
            // A popup is intentionally used rather than an in-flow FlowRow. Queue rows live in a
            // bounded scrolling viewport; an in-flow list is clipped below the row (the exact
            // failure shown in the live workspace) and gives the impression that no lifelines
            // exist. This layer escapes the viewport and stays anchored to its endpoint button.
            Popup(
                alignment = Alignment.TopEnd,
                offset = IntOffset(0, anchorHeightPx + 3),
                onDismissRequest = { expanded = false },
                properties = PopupProperties(focusable = true),
            ) {
                Column(
                    Modifier.width(menuWidth)
                        .background(tc().p, CORNER_SM)
                        .border(1.dp, tc().br, CORNER_SM)
                        .padding(4.dp),
                    verticalArrangement = Arrangement.spacedBy(3.dp),
                ) {
                if (allowNeedsTarget) {
                    AppButton(
                        "Needs target",
                        { expanded = false; onSelect("") },
                        variant = if (selected == "Needs target") ButtonVariant.Primary else ButtonVariant.Ghost,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                lifelineIds.forEach { id ->
                    AppButton(
                        id,
                        { expanded = false; onSelect(id) },
                        variant = if (id == selected) ButtonVariant.Primary else ButtonVariant.Ghost,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                }
            }
        }
    }
}

@Composable
private fun GuidedTargetPassCard(
    spec: SeqDiagramSpec,
    lifelineIds: List<String>,
    entries: List<LogEntry>,
    state: GuidedTargetPassState,
    onExit: () -> Unit,
    onNavigateEvidence: (Int) -> Unit,
    onChooseTarget: (String, MessageKind, Boolean) -> Unit,
    onSkip: () -> Unit,
    onCreateLifeline: (String, Boolean) -> Unit,
) {
    val row = guidedTargetRow(spec.manualDocument, state)
    if (row == null) {
        AppText("All unresolved targets are complete.", color = tc().td, fontSize = 10.sp, modifier = Modifier.padding(horizontal = 12.dp))
        AppButton("Close guided pass", onExit, variant = ButtonVariant.Ghost, modifier = Modifier.padding(horizontal = 12.dp))
        return
    }
    val choices = lifelineIds.take(9)
    // Suggestions must resolve evidence tags back to the durable lifeline IDs used by manual
    // interactions. A component ID is often different from each raw tag it owns, so synthetic
    // `DiagramParticipant(id = componentId, tag = componentId)` values would suppress valid
    // same-thread suggestions for component-backed lifelines.
    val suggestionParticipants = buildList {
        spec.components.filter { it.enabled }.forEach { component ->
            component.tagIds.forEach { tag ->
                add(DiagramParticipant(component.id, component.displayName, ParticipantKind.TAG, tag = tag))
            }
        }
        spec.actors.forEach { actor ->
            add(DiagramParticipant(actor.id, actor.label, ParticipantKind.ACTOR))
        }
        spec.participants.forEach { participant ->
            if (none { it.id == participant.id && it.tag == participant.tag }) add(participant)
        }
    }
    val suggestion = suggestManualTarget(
        row.representative,
        entries,
        suggestionParticipants,
    )
    var selectedTarget by remember(row.id, suggestion?.participantId, choices) {
        mutableStateOf(suggestion?.participantId ?: choices.firstOrNull())
    }
    var applyToAllOccurrences by remember(row.id) { mutableStateOf(true) }
    var newLifeline by remember(row.id) { mutableStateOf("") }
    val context = guidedTargetContext(row, entries)
    val firstEvidenceId = row.sourceEntryIds.minOrNull()
    val currentNumber = state.currentIndex + 1
    val total = state.groupIds.size
    val guidedFocusRequester = remember(row.id) { FocusRequester() }
    LaunchedEffect(row.id) { runCatching { guidedFocusRequester.requestFocus() } }
    Column(
        Modifier.fillMaxWidth()
            .background(tc().p, CORNER_MD)
            .border(1.dp, tc().br, CORNER_MD)
            .padding(12.dp)
                .focusRequester(guidedFocusRequester)
                .focusable()
                .onKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) {
                    false
                } else {
                    when (event.key) {
                        Key.Enter -> {
                            selectedTarget?.let { target ->
                                onChooseTarget(
                                    target,
                                    if (target == row.fromParticipantId) MessageKind.SELF else MessageKind.CALL,
                                    applyToAllOccurrences,
                                )
                            }
                            true
                        }
                        Key.S -> {
                            onSkip()
                            true
                        }
                        Key.Escape -> {
                            onExit()
                            true
                        }
                        Key.One, Key.Two, Key.Three, Key.Four, Key.Five, Key.Six, Key.Seven, Key.Eight, Key.Nine -> {
                            val index = MANUAL_DIGIT_KEYS.indexOf(event.key)
                            choices.getOrNull(index)?.let { selectedTarget = it }
                            true
                        }
                        else -> false
                    }
                }
                },
            verticalArrangement = Arrangement.spacedBy(5.dp),
        ) {
        SectionHeader(
            "Set targets",
            trailing = { AppText(currentNumber.toString() + " / " + total, color = tc().td, fontSize = 9.sp) },
        )
        AppText(
            row.fromParticipantId + " → Needs target",
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
        )
        AppText(
            manualMessageTemplate(row.representative) + " · " + row.occurrenceCount + " occurrence(s)",
            color = tc().td,
            fontSize = 10.sp,
            maxLines = 2,
        )
        if (suggestion != null) {
            AppText(
                "Suggested from evidence: " + suggestion.participantId + " (" + suggestion.reason + "). Select and confirm.",
                color = tc().ac,
                fontSize = 9.sp,
                maxLines = 2,
            )
        }
        context.forEach { entry ->
            AppText(
                entry.id.toString() + "  " + entry.ts + "  " + entry.tag + ": " + entry.msg,
                color = if (entry.id in row.sourceEntryIds) tc().ac else tc().td,
                fontSize = 9.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (firstEvidenceId != null) {
            AppButton(
                "Open evidence row " + firstEvidenceId,
                { onNavigateEvidence(firstEvidenceId) },
                variant = ButtonVariant.Ghost,
            )
        }
        choices.forEachIndexed { index, id ->
            AppButton(
                (index + 1).toString() + ". " + id + if (id == suggestion?.participantId) " · suggested" else "",
                { selectedTarget = id },
                variant = if (id == selectedTarget) ButtonVariant.Primary else ButtonVariant.Secondary,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            AppButton(
                "Apply target",
                {
                    selectedTarget?.let { target ->
                        onChooseTarget(
                            target,
                            if (target == row.fromParticipantId) MessageKind.SELF else MessageKind.CALL,
                            applyToAllOccurrences,
                        )
                    }
                },
                variant = ButtonVariant.Primary,
                enabled = selectedTarget != null,
            )
            AppButton(
                "Make self-call",
                { onChooseTarget(row.fromParticipantId, MessageKind.SELF, applyToAllOccurrences) },
                variant = ButtonVariant.Ghost,
            )
            AppButton("Skip", onSkip, variant = ButtonVariant.Ghost)
            AppButton("Esc", onExit, variant = ButtonVariant.Ghost)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(5.dp), verticalAlignment = Alignment.CenterVertically) {
            InlineField(
                newLifeline,
                { newLifeline = it },
                "new declared lifeline",
                Modifier.weight(1f),
                fontSize = 10.sp,
            )
            AppButton(
                "New lifeline",
                { onCreateLifeline(newLifeline, applyToAllOccurrences) },
                variant = ButtonVariant.Secondary,
                enabled = newLifeline.trim().isNotEmpty(),
            )
        }
        if (row.occurrenceCount > 1) {
            CheckRow(checked = applyToAllOccurrences, onToggle = { applyToAllOccurrences = !applyToAllOccurrences }) {
                AppText("Apply to all ×${row.occurrenceCount} occurrences", fontSize = 10.sp)
            }
        }
        AppText("Enter apply · 1–9 select · S skip · Esc exit", color = tc().td, fontSize = 9.sp)
    }
}

internal fun manualGroupKeyAtY(
    groupKeys: List<String>,
    y: Float,
    heightOf: (String) -> Float,
): String? {
    if (y < 0f) return null
    var top = 0f
    for (groupKey in groupKeys) {
        val bottom = top + heightOf(groupKey)
        if (y < bottom) return groupKey
        top = bottom
    }
    return null
}

@Composable
private fun ManualRoundToggle(
    tooltip: String,
    active: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    color: Color = tc().ac,
    indeterminate: Boolean = false,
) {
    TooltipArea(tooltip = { ToolbarTooltip(tooltip) }) {
        RoundIndicator(
            active = active,
            color = color,
            indeterminate = indeterminate,
            onClick = onClick,
            modifier = modifier,
        )
    }
}

private fun nextManualOrder(document: ManualDiagramDocument): Long =
    (document.interactions.maxOfOrNull { it.order } ?: -1L) + 1L

@Composable
private fun ManualDocumentAuxEditors(
    spec: SeqDiagramSpec,
    lifelineIds: List<String>,
    selectedInteractionIds: Set<String>,
    workspaceKey: String,
    onSpec: (SeqDiagramSpec) -> Unit,
    onClearAll: () -> Unit,
) {
    val document = spec.manualDocument
    val interactionIds = document.interactions.map { it.id }
    val selected = document.interactions.filter { it.id in selectedInteractionIds }.sortedBy { it.order }
    var expanded by remember(workspaceKey) { mutableStateOf(false) }
    var confirmClear by remember(workspaceKey) { mutableStateOf(false) }
    SectionHeader(
        "Advanced structure",
        trailing = { AppText("${document.groups.size + document.notes.size + document.activations.size}", color = tc().td, fontSize = 9.sp) },
        expanded = expanded,
        onToggle = { expanded = !expanded },
    )
    if (!expanded) return
    AppText(
        "Use the first dot to enable a line. Use the second dot to select lines for a frame, note, or activation. Existing structure remains editable below.",
        color = tc().td, fontSize = 9.sp, maxLines = 3, modifier = Modifier.padding(horizontal = 12.dp),
    )
    AppButton(
        if (selected.size >= 2) "Add frame/group (${selected.size} selected)" else "Select 2+ lines for frame/group",
        {
            val group = ManualDiagramGroup("group-${System.nanoTime()}", "Group", selected.map { it.id })
            onSpec(spec.copy(manualDocument = document.copy(groups = document.groups + group)))
        },
        variant = ButtonVariant.Ghost,
        enabled = selected.size >= 2,
        modifier = Modifier.padding(horizontal = 12.dp),
    )
    document.groups.forEach { group ->
        Column(Modifier.padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CompactCheckBox(checked = group.enabled, onToggle = {
                    val groups = document.groups.map { if (it.id == group.id) it.copy(enabled = !it.enabled) else it }
                    onSpec(spec.copy(manualDocument = document.copy(groups = groups)))
                })
                InlineField(
                    group.label,
                    { value ->
                        val groups = document.groups.map { if (it.id == group.id) it.copy(label = value) else it }
                        onSpec(spec.copy(manualDocument = document.copy(groups = groups)))
                    },
                    "frame label", Modifier.weight(1f), fontSize = 10.sp,
                )
                AppText("${group.interactionIds.size} lines", color = tc().td, fontSize = 9.sp)
                AppButton(
                    "×",
                    { onSpec(spec.copy(manualDocument = document.copy(groups = document.groups.filterNot { it.id == group.id }))) },
                    variant = ButtonVariant.Ghost,
                )
            }
            ManualFragmentKindChoice(group.kind) { kind ->
                val groups = document.groups.map { if (it.id == group.id) it.copy(kind = kind) else it }
                val interactions = document.interactions.map { interaction ->
                    if (interaction.id in group.interactionIds) {
                        interaction.copy(authoring = ManualInteractionAuthoring.EDITED)
                    } else {
                        interaction
                    }
                }
                onSpec(spec.copy(manualDocument = document.copy(groups = groups, interactions = interactions)))
            }
        }
    }

    AppButton(
        if (selected.size == 1) "Add note after selected line" else "Select 1 line for note",
        {
            val participant = selected.firstOrNull()?.fromParticipantId ?: return@AppButton
            val anchor = selected.singleOrNull()?.id ?: return@AppButton
            val note = ManualDiagramNote("note-${System.nanoTime()}", participant, anchor, "Note")
            onSpec(spec.copy(manualDocument = document.copy(notes = document.notes + note)))
        },
        variant = ButtonVariant.Ghost,
        enabled = selected.size == 1 && lifelineIds.isNotEmpty(),
        modifier = Modifier.padding(horizontal = 12.dp),
    )
    document.notes.forEach { note ->
        Column(Modifier.padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CompactCheckBox(
                    checked = note.enabled,
                    onToggle = {
                        val notes = document.notes.map { if (it.id == note.id) it.copy(enabled = !it.enabled) else it }
                        onSpec(spec.copy(manualDocument = document.copy(notes = notes)))
                    },
                )
                InlineField(
                    note.text,
                    { value ->
                        val notes = document.notes.map { if (it.id == note.id) it.copy(text = value) else it }
                        onSpec(spec.copy(manualDocument = document.copy(notes = notes)))
                    },
                    "note", Modifier.weight(1f), fontSize = 10.sp,
                )
                AppButton(
                    "×",
                    { onSpec(spec.copy(manualDocument = document.copy(notes = document.notes.filterNot { it.id == note.id }))) },
                    variant = ButtonVariant.Ghost,
                )
            }
            LifelineChoice("Participant", note.participantId, lifelineIds) { id ->
                val notes = document.notes.map { if (it.id == note.id) it.copy(participantId = id) else it }
                onSpec(spec.copy(manualDocument = document.copy(notes = notes)))
            }
            InteractionChoice("After", note.afterInteractionId, interactionIds) { id ->
                val notes = document.notes.map { if (it.id == note.id) it.copy(afterInteractionId = id) else it }
                onSpec(spec.copy(manualDocument = document.copy(notes = notes)))
            }
        }
    }

    AppButton(
        if (selected.isNotEmpty()) "Add activation for selected span" else "Select a line span for activation",
        {
            val participant = selected.firstOrNull()?.fromParticipantId ?: return@AppButton
            val activation = ManualDiagramActivation("activation-${System.nanoTime()}", participant, selected.first().id, selected.last().id)
            onSpec(spec.copy(manualDocument = document.copy(activations = document.activations + activation)))
        },
        variant = ButtonVariant.Ghost,
        enabled = selected.isNotEmpty(),
        modifier = Modifier.padding(horizontal = 12.dp),
    )
    document.activations.forEach { activation ->
        Column(Modifier.padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CompactCheckBox(
                    checked = activation.enabled,
                    onToggle = {
                        val activations = document.activations.map { if (it.id == activation.id) it.copy(enabled = !it.enabled) else it }
                        onSpec(spec.copy(manualDocument = document.copy(activations = activations)))
                    },
                )
                AppText("Activation", fontSize = 10.sp, modifier = Modifier.weight(1f))
                AppButton(
                    "×",
                    { onSpec(spec.copy(manualDocument = document.copy(activations = document.activations.filterNot { it.id == activation.id }))) },
                    variant = ButtonVariant.Ghost,
                )
            }
            LifelineChoice("Participant", activation.participantId, lifelineIds) { id ->
                val activations = document.activations.map { if (it.id == activation.id) it.copy(participantId = id) else it }
                onSpec(spec.copy(manualDocument = document.copy(activations = activations)))
            }
            InteractionChoice("Start", activation.startInteractionId, interactionIds) { id ->
                val activations = document.activations.map { if (it.id == activation.id) it.copy(startInteractionId = id) else it }
                onSpec(spec.copy(manualDocument = document.copy(activations = activations)))
            }
            InteractionChoice("End", activation.endInteractionId, interactionIds) { id ->
                val activations = document.activations.map { if (it.id == activation.id) it.copy(endInteractionId = id) else it }
                onSpec(spec.copy(manualDocument = document.copy(activations = activations)))
            }
        }
    }

    if (!confirmClear) {
        AppButton(
            "Clear all interactions", { confirmClear = true },
            variant = ButtonVariant.Ghost, isDanger = true, modifier = Modifier.padding(horizontal = 12.dp),
        )
    } else {
        AppText(
            "This removes interactions, frames, notes, and activations.",
            color = DANGER_RED, fontSize = 9.sp, modifier = Modifier.padding(horizontal = 12.dp),
        )
        Row(Modifier.padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            AppButton("Cancel", { confirmClear = false }, variant = ButtonVariant.Ghost)
            AppButton("Clear", { confirmClear = false; onClearAll() }, variant = ButtonVariant.Secondary, isDanger = true)
        }
    }
}

@Composable
private fun InteractionChoice(label: String, selected: String, interactionIds: List<String>, onSelect: (String) -> Unit) {
    var expanded by remember(label, selected) { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        AppText(label, color = tc().td, fontSize = 9.sp)
        AppButton("$selected ▾", { expanded = !expanded }, variant = ButtonVariant.Secondary, modifier = Modifier.fillMaxWidth())
        if (expanded) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(3.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                interactionIds.forEach { id ->
                    AppButton(id, { expanded = false; onSelect(id) }, variant = if (id == selected) ButtonVariant.Primary else ButtonVariant.Ghost)
                }
            }
        }
    }
}

@Composable
private fun ManualInteractionCard(
    interaction: ManualDiagramInteraction,
    lifelineIds: List<String>,
    onChange: (ManualDiagramInteraction) -> Unit,
    onDelete: () -> Unit,
    onDuplicate: () -> Unit,
    showActions: Boolean = true,
) {
    val tc = tc()
    var parameters by remember(interaction.id, interaction.parameters) { mutableStateOf(interaction.parameters.formatParameters()) }
    Column(Modifier.padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            ManualRoundToggle(
                tooltip = if (interaction.enabled) "Disable interaction" else "Enable interaction",
                active = interaction.enabled,
                onClick = { onChange(interaction.copy(enabled = !interaction.enabled)) },
            )
            AppText(
                interaction.operation.ifBlank { "Untitled interaction" },
                fontSize = 11.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f), maxLines = 1,
            )
            if (showActions) {
                AppButton("Duplicate", onDuplicate, variant = ButtonVariant.Ghost)
                AppButton("×", onDelete, variant = ButtonVariant.Ghost)
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            AppText("From", color = tc.td, fontSize = 9.sp)
            QueueEndpointChoice(interaction.fromParticipantId, lifelineIds) {
            onChange(interaction.copy(
                fromParticipantId = it,
                kind = manualKindAfterEndpointChange(interaction.kind, it, interaction.toParticipantId),
            ))
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            AppText("To", color = tc.td, fontSize = 9.sp)
            QueueEndpointChoice(interaction.toParticipantId ?: "Needs target", lifelineIds, allowNeedsTarget = true) {
                val target = it.takeUnless(String::isBlank)
            onChange(interaction.copy(
                toParticipantId = target,
                kind = manualKindAfterEndpointChange(interaction.kind, interaction.fromParticipantId, target),
            ))
            }
        }
        InlineField(interaction.operation, { onChange(interaction.copy(operation = it)) }, "operation", Modifier.fillMaxWidth(), fontSize = 10.sp)
        InlineField(
            interaction.label.orEmpty(), { value -> onChange(interaction.copy(label = value.ifBlank { null })) },
            "custom label (optional)", Modifier.fillMaxWidth(), fontSize = 10.sp,
        )
        InlineField(
            interaction.result.orEmpty(), { value -> onChange(interaction.copy(result = value.ifBlank { null })) },
            "result label (optional)", Modifier.fillMaxWidth(), fontSize = 10.sp,
        )
        InlineField(parameters, { value ->
            parameters = value
            onChange(interaction.copy(parameters = value.parseParameters()))
        }, "parameters: name=value; …", Modifier.fillMaxWidth(), fontSize = 10.sp)
        val kinds = listOf(MessageKind.CALL, MessageKind.RETURN, MessageKind.SELF, MessageKind.ASYNC)
        SegmentedControl(kinds.map { it.name.lowercase() }, setOf(kinds.indexOf(interaction.kind)), onToggle = { index ->
            onChange(interaction.copy(kind = kinds[index]))
        })
        ManualVisibilityChoice(interaction.visibility) { onChange(interaction.copy(visibility = it)) }
        AppText("Only configured lifelines are selectable. Parameters are shown in the operation label.", color = tc.td, fontSize = 9.sp, maxLines = 2)
    }
}

@Composable
private fun LifelineChoice(label: String, selected: String, lifelineIds: List<String>, onSelect: (String) -> Unit) {
    var expanded by remember(label, selected) { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        AppText(label, color = tc().td, fontSize = 9.sp)
        AppButton(
            "$selected ▾",
            { expanded = !expanded },
            variant = ButtonVariant.Secondary,
            modifier = Modifier.fillMaxWidth(),
        )
        if (expanded) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(3.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                lifelineIds.forEach { id ->
                    AppButton(id, { expanded = false; onSelect(id) }, variant = if (id == selected) ButtonVariant.Primary else ButtonVariant.Ghost)
                }
            }
        }
    }
}

@Composable
private fun ManualVisibilityChoice(value: ManualOperationVisibility, onSelect: (ManualOperationVisibility) -> Unit) {
    val values = ManualOperationVisibility.entries
    SegmentedControl(
        values.map { it.name.lowercase().replace('_', ' ') },
        setOf(values.indexOf(value)),
        onToggle = { index -> onSelect(values[index]) },
    )
}

private fun List<DiagramParameter>.formatParameters(): String = joinToString("; ") { parameter ->
    if (parameter.name.isBlank()) parameter.value else "${parameter.name}=${parameter.value}"
}

private fun String.parseParameters(): List<DiagramParameter> = split(';').mapNotNull { raw ->
    val item = raw.trim()
    if (item.isBlank()) return@mapNotNull null
    val equals = item.indexOf('=')
    if (equals < 0) DiagramParameter(value = item)
    else DiagramParameter(item.substring(0, equals).trim(), item.substring(equals + 1).trim())
}

internal fun manualKindAfterEndpointChange(kind: MessageKind, fromParticipantId: String, toParticipantId: String?): MessageKind =
    when {
        toParticipantId == null -> MessageKind.CALL
        fromParticipantId == toParticipantId && kind == MessageKind.CALL -> MessageKind.SELF
        fromParticipantId != toParticipantId && kind == MessageKind.SELF -> MessageKind.CALL
        else -> kind
    }
